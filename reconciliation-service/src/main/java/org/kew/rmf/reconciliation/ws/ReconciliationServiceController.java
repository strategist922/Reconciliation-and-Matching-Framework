/*
 * Reconciliation and Matching Framework
 * Copyright © 2014 Royal Botanic Gardens, Kew
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.kew.rmf.reconciliation.ws;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.servlet.ServletContext;
import javax.servlet.http.HttpServletRequest;

import org.codehaus.jackson.JsonGenerationException;
import org.codehaus.jackson.map.JsonMappingException;
import org.codehaus.jackson.map.ObjectMapper;
import org.codehaus.jackson.type.TypeReference;
import org.kew.rmf.core.configuration.Configuration;
import org.kew.rmf.core.configuration.Property;
import org.kew.rmf.core.exception.MatchExecutionException;
import org.kew.rmf.core.exception.TooManyMatchesException;
import org.kew.rmf.reconciliation.exception.UnknownReconciliationServiceException;
import org.kew.rmf.reconciliation.queryextractor.QueryStringToPropertiesExtractor;
import org.kew.rmf.reconciliation.service.ReconciliationService;
import org.kew.rmf.refine.domain.metadata.Metadata;
import org.kew.rmf.refine.domain.metadata.Type;
import org.kew.rmf.refine.domain.query.Query;
import org.kew.rmf.refine.domain.response.FlyoutResponse;
import org.kew.rmf.refine.domain.response.QueryResponse;
import org.kew.rmf.refine.domain.response.QueryResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.client.RestTemplate;

/**
 * Implements an <a href="https://github.com/OpenRefine/OpenRefine/wiki/Reconciliation-Service-Api">OpenRefine Reconciliation Service</a>
 * on top of a match configuration.
 */
@Controller
public class ReconciliationServiceController {
	private static Logger logger = LoggerFactory.getLogger(ReconciliationServiceController.class);

	@Autowired
	private ReconciliationService reconciliationService;

	@Autowired
	private BaseController baseController;

	@Autowired
	private ServletContext servletContext;

	@Autowired
	private ObjectMapper jsonMapper;

	private RestTemplate template = new RestTemplate();

	/**
	 * Retrieve reconciliation service metadata.
	 */
	@RequestMapping(value = "/reconcile/{configName}", method={RequestMethod.GET,RequestMethod.POST}, produces="application/json; charset=UTF-8")
	public ResponseEntity<String> getMetadata(HttpServletRequest request, @PathVariable String configName, @RequestParam(value="callback",required=false) String callback, Model model) throws JsonGenerationException, JsonMappingException, IOException {
		logger.info("{}: Get Metadata request", configName);

		String myUrl = request.getScheme() + "://" + request.getServerName() + (request.getServerPort() == 80 ? "" : (":" + request.getServerPort()));
		String basePath = servletContext.getContextPath() + "/reconcile/" + configName;

		Metadata metadata;
		try {
			metadata = reconciliationService.getMetadata(configName);
		}
		catch (UnknownReconciliationServiceException e) {
			return new ResponseEntity<String>(e.toString(), baseController.getResponseHeaders(), HttpStatus.NOT_FOUND);
		}
		catch (MatchExecutionException e) {
			return new ResponseEntity<String>(e.toString(), baseController.getResponseHeaders(), HttpStatus.INTERNAL_SERVER_ERROR);
		}

		if (metadata != null) {
			String metadataJson = jsonMapper.writeValueAsString(metadata).replace("LOCAL", myUrl).replace("BASE", basePath);
			return new ResponseEntity<String>(wrapResponse(callback, metadataJson), baseController.getResponseHeaders(), HttpStatus.OK);
		}
		return null;
	}

	/**
	 * Perform multiple reconciliation queries (no callback)
	 */
	@RequestMapping(value = "/reconcile/{configName}", method={RequestMethod.GET,RequestMethod.POST}, params={"queries"}, produces="application/json; charset=UTF-8")
	public ResponseEntity<String> doMultipleQueries(@PathVariable String configName, @RequestParam("queries") String queries) {
		return doMultipleQueries(configName, queries, null);
	}

	/**
	 * Perform multiple reconciliation queries (no callback)
	 */
	@RequestMapping(value = "/reconcile/{configName}", method={RequestMethod.GET,RequestMethod.POST}, params={"queries","callback"}, produces="application/json; charset=UTF-8")
	public ResponseEntity<String> doMultipleQueries(@PathVariable String configName, @RequestParam("queries") String queries, @RequestParam(value="callback",required=false) String callback) {
		logger.info("{}: Multiple query request {}", configName, queries);

		String jsonres = null;
		Map<String,QueryResponse<QueryResult>> res = new HashMap<>();
		try {
			// Convert JSON to map of queries
			Map<String,Query> qs = jsonMapper.readValue(queries, new TypeReference<Map<String,Query>>() {});
			for (String key : qs.keySet()) {
				try {
					Query q = qs.get(key);
					QueryResult[] qres = doQuery(q, configName);
					QueryResponse<QueryResult> response = new QueryResponse<>();
					response.setResult(qres);
					res.put(key,response);
				}
				catch (MatchExecutionException | TooManyMatchesException e) {
					// Only fail the one query, not the whole batch
					logger.warn("{}: Query with key {} of multiple query call failed: {}", configName, key, e.getMessage());
				}
			}
			jsonres = jsonMapper.writeValueAsString(res);
		}
		catch (Exception e) {
			logger.error(configName + ": Error with multiple query call", e);
		}
		return new ResponseEntity<String>(wrapResponse(callback, jsonres), baseController.getResponseHeaders(), HttpStatus.OK);
	}

	/**
	 * Single reconciliation query, no callback.
	 */
	@RequestMapping(value = "/reconcile/{configName}", method={RequestMethod.GET,RequestMethod.POST}, params={"query"}, produces="application/json; charset=UTF-8")
	public ResponseEntity<String> doSingleQuery(@PathVariable String configName, @RequestParam("query") String query) {
		return doSingleQuery(configName, query, null);
	}

	/**
	 * Single reconciliation query.
	 */
	@RequestMapping(value = "/reconcile/{configName}", method={RequestMethod.GET,RequestMethod.POST}, params={"query","callback"}, produces="application/json; charset=UTF-8")
	public ResponseEntity<String> doSingleQuery(@PathVariable String configName, @RequestParam("query") String query, @RequestParam(value="callback",required=false) String callback) {
		logger.info("{}: Single query request {}", configName, query);

		String jsonres = null;
		try {
			Query q = jsonMapper.readValue(query, Query.class);
			QueryResult[] qres = doQuery(q, configName);
			QueryResponse<QueryResult> response = new QueryResponse<>();
			response.setResult(qres);
			jsonres = jsonMapper.writeValueAsString(response);
		}
		catch (JsonMappingException | JsonGenerationException e) {
			logger.warn(configName + ": Error parsing JSON query", e);
			return new ResponseEntity<String>(e.toString(), baseController.getResponseHeaders(), HttpStatus.INTERNAL_SERVER_ERROR);
		}
		catch (IOException e) {
			logger.error(configName + ": Query failed:", e);
			return new ResponseEntity<String>(e.toString(), baseController.getResponseHeaders(), HttpStatus.INTERNAL_SERVER_ERROR);
		}
		catch (UnknownReconciliationServiceException e) {
			logger.warn(configName + ": Query failed:", e);
			return new ResponseEntity<String>(e.toString(), baseController.getResponseHeaders(), HttpStatus.NOT_FOUND);
		}
		catch (MatchExecutionException e) {
			logger.error(configName + ": Query failed:", e);
			return new ResponseEntity<String>(e.toString(), baseController.getResponseHeaders(), HttpStatus.INTERNAL_SERVER_ERROR);
		}
		catch (TooManyMatchesException e) {
			logger.warn(configName + ": Query failed:", e);
			return new ResponseEntity<String>(e.toString(), baseController.getResponseHeaders(), HttpStatus.CONFLICT);
		}

		return new ResponseEntity<String>(wrapResponse(callback, jsonres), baseController.getResponseHeaders(), HttpStatus.OK);
	}

	/**
	 * Single suggest query, no callback.
	 */
	@RequestMapping(value = "/reconcile/{configName}", method={RequestMethod.GET,RequestMethod.POST}, params={"prefix"}, produces="application/json; charset=UTF-8")
	public ResponseEntity<String> doSuggest(@PathVariable String configName, @RequestParam("prefix") String prefix) {
		return doSuggest(configName, prefix, null);
	}

	/**
	 * Single suggest query.
	 */
	@RequestMapping(value = "/reconcile/{configName}", method={RequestMethod.GET,RequestMethod.POST}, params={"prefix","callback"}, produces="application/json; charset=UTF-8")
	public ResponseEntity<String> doSuggest(@PathVariable String configName, @RequestParam("prefix") String prefix, @RequestParam(value="callback",required=false) String callback) {
		logger.info("{}: Suggest query, prefix {}", configName, prefix);

		Query q = new Query();
		q.setQuery(prefix);

		try {
			return doSingleQuery(configName, jsonMapper.writeValueAsString(q), callback);
		}
		catch (IOException e) {
			return new ResponseEntity<String>(e.toString(), baseController.getResponseHeaders(), HttpStatus.INTERNAL_SERVER_ERROR);
		}
	}

	/**
	 * Type suggest, no callback.
	 */
	@RequestMapping(value = "/reconcile/{configName}/suggestType", method={RequestMethod.GET,RequestMethod.POST}, params={"prefix"}, produces="application/json; charset=UTF-8")
	public ResponseEntity<String> doSuggestType(@PathVariable String configName, @RequestParam("prefix") String prefix) {
		return doSuggestType(configName, prefix, null);
	}

	/**
	 * Type suggest.
	 */
	@RequestMapping(value = "/reconcile/{configName}/suggestType", method={RequestMethod.GET,RequestMethod.POST}, params={"prefix","callback"}, produces="application/json; charset=UTF-8")
	public ResponseEntity<String> doSuggestType(@PathVariable String configName, @RequestParam("prefix") String prefix, @RequestParam(value="callback",required=false) String callback) {
		logger.info("{}: Type suggest query, prefix {}", configName, prefix);

		String jsonres = null;
		try {
			Type[] defaultTypes = reconciliationService.getMetadata(configName).getDefaultTypes();
			QueryResponse<Type> response = new QueryResponse<>();
			response.setResult(defaultTypes);
			jsonres = jsonMapper.writeValueAsString(response);
		}
		catch (JsonMappingException | JsonGenerationException e) {
			logger.warn(configName + ": Error parsing JSON query", e);
			return new ResponseEntity<String>(e.toString(), baseController.getResponseHeaders(), HttpStatus.INTERNAL_SERVER_ERROR);
		}
		catch (IOException e) {
			return new ResponseEntity<String>(e.toString(), baseController.getResponseHeaders(), HttpStatus.INTERNAL_SERVER_ERROR);
		}
		catch (UnknownReconciliationServiceException e) {
			return new ResponseEntity<String>(e.toString(), baseController.getResponseHeaders(), HttpStatus.NOT_FOUND);
		}
		catch (MatchExecutionException e) {
			return new ResponseEntity<String>(e.toString(), baseController.getResponseHeaders(), HttpStatus.INTERNAL_SERVER_ERROR);
		}

		return new ResponseEntity<String>(wrapResponse(callback, jsonres), baseController.getResponseHeaders(), HttpStatus.OK);
	}

	/**
	 * Type suggest flyout
	 */
	@RequestMapping(value = "/reconcile/{configName}/flyoutType/{id:.+}", method={RequestMethod.GET,RequestMethod.POST}, produces="application/json; charset=UTF-8")
	public ResponseEntity<String> doTypeFlyout(@PathVariable String configName, @PathVariable String id, @RequestParam(value="callback",required=false) String callback) {
		logger.info("{}: Type flyout for id {}", configName, id);

		try {
			Type[] defaultTypes = reconciliationService.getMetadata(configName).getDefaultTypes();
			Type type = null;

			for (Type t : defaultTypes) {
				if (t.getId().equals(id)) {
					type = t;
				}
			}

			String html = "<html><body><ul><li>"+type.getName()+" ("+type.getId()+")</li></ul></body></html>\n";
			FlyoutResponse jsonWrappedHtml = new FlyoutResponse(html);

			return new ResponseEntity<String>(wrapResponse(callback, jsonMapper.writeValueAsString(jsonWrappedHtml)), baseController.getResponseHeaders(), HttpStatus.OK);
		}
		catch (IOException e) {
			logger.warn(configName + ": Error in type flyout for id "+id, e);
			return new ResponseEntity<String>(e.toString(), baseController.getResponseHeaders(), HttpStatus.INTERNAL_SERVER_ERROR);
		}
		catch (UnknownReconciliationServiceException e) {
			return new ResponseEntity<String>(e.toString(), baseController.getResponseHeaders(), HttpStatus.NOT_FOUND);
		}
		catch (MatchExecutionException | NullPointerException e) {
			return new ResponseEntity<String>(e.toString(), baseController.getResponseHeaders(), HttpStatus.INTERNAL_SERVER_ERROR);
		}
	}

	/**
	 * Properties suggest, no callback.
	 */
	@RequestMapping(value = "/reconcile/{configName}/suggestProperty", method={RequestMethod.GET,RequestMethod.POST}, params={"prefix"}, produces="application/json; charset=UTF-8")
	public ResponseEntity<String> doSuggestProperty(@PathVariable String configName, @RequestParam("prefix") String prefix) {
		return doSuggestProperty(configName, prefix, null);
	}

	/**
	 * Properties suggest.
	 */
	@RequestMapping(value = "/reconcile/{configName}/suggestProperty", method={RequestMethod.GET,RequestMethod.POST}, params={"prefix","callback"}, produces="application/json; charset=UTF-8")
	public ResponseEntity<String> doSuggestProperty(@PathVariable String configName, @RequestParam("prefix") String prefix, @RequestParam(value="callback",required=false) String callback) {
		logger.info("{}: Property suggest query, prefix {}", configName, prefix);

		String jsonres = null;
		try {
			List<Type> filteredProperties = new ArrayList<>();
			List<Property> properties = reconciliationService.getReconciliationServiceConfiguration(configName).getProperties();
			for (Property p : properties) {
				String name = p.getQueryColumnName();

				// Filter by prefix
				if (name != null && name.toUpperCase().startsWith(prefix.toUpperCase())) {
					Type t = new Type();
					t.setId(name);
					t.setName(name);
					filteredProperties.add(t);
				}
			}
			logger.debug("Suggest Property query for {} filtered {} properties to {}", prefix, properties.size(), filteredProperties);

			QueryResponse<Type> response = new QueryResponse<>();
			response.setResult(filteredProperties.toArray(new Type[1]));
			jsonres = jsonMapper.writeValueAsString(response);
		}
		catch (JsonMappingException | JsonGenerationException e) {
			logger.warn(configName + ": Error parsing JSON query", e);
			return new ResponseEntity<String>(e.toString(), baseController.getResponseHeaders(), HttpStatus.INTERNAL_SERVER_ERROR);
		}
		catch (IOException e) {
			return new ResponseEntity<String>(e.toString(), baseController.getResponseHeaders(), HttpStatus.INTERNAL_SERVER_ERROR);
		}
		catch (UnknownReconciliationServiceException e) {
			return new ResponseEntity<String>(e.toString(), baseController.getResponseHeaders(), HttpStatus.NOT_FOUND);
		}

		return new ResponseEntity<String>(wrapResponse(callback, jsonres), baseController.getResponseHeaders(), HttpStatus.OK);
	}

	/**
	 * Properties suggest flyout
	 */
	@RequestMapping(value = "/reconcile/{configName}/flyoutProperty/{id:.+}", method={RequestMethod.GET,RequestMethod.POST}, produces="application/json; charset=UTF-8")
	public ResponseEntity<String> doPropertiesFlyout(@PathVariable String configName, @PathVariable String id, @RequestParam(value="callback",required=false) String callback) {
		logger.info("{}: In property flyout for id {}", configName, id);

		try {
			String html = "<html><body><ul><li>"+id+"</li></ul></body></html>\n";
			FlyoutResponse jsonWrappedHtml = new FlyoutResponse(html);

			return new ResponseEntity<String>(wrapResponse(callback, jsonMapper.writeValueAsString(jsonWrappedHtml)), baseController.getResponseHeaders(), HttpStatus.OK);
		}
		catch (IOException e) {
			logger.warn(configName + ": Error in properties flyout for id "+id, e);
			return new ResponseEntity<String>(e.toString(), baseController.getResponseHeaders(), HttpStatus.INTERNAL_SERVER_ERROR);
		}
	}

	/**
	 * Entity suggest flyout.
	 * <br/>
	 * Either calls the URL provided in the configuration, or generates an HTML snippet containing the known Property fields (in order) in a table.
	 */
	@RequestMapping(value = "/reconcile/{configName}/flyout/{id:.+}", method={RequestMethod.GET,RequestMethod.POST}, produces="application/json; charset=UTF-8")
	public ResponseEntity<String> doSuggestFlyout(@PathVariable String configName, @PathVariable String id, @RequestParam(value="callback",required=false) String callback) {
		logger.info("{}: Suggest flyout request for {}", configName, id);

		// TODO: This should be replaced by a class which is customisable, e.g. to return HTML, or transform RDF.
		String targetUrl;

		try {
			targetUrl = reconciliationService.getReconciliationServiceConfiguration(configName).getSuggestFlyoutUrl();
		}
		catch (UnknownReconciliationServiceException e) {
			return new ResponseEntity<String>(e.toString(), baseController.getResponseHeaders(), HttpStatus.NOT_FOUND);
		}

		// If the configuration has a flyout configured use it
		if (targetUrl != null) {
			try {
				ResponseEntity<String> httpResponse = template.getForEntity(targetUrl, String.class, id);

				if (httpResponse.getStatusCode() != HttpStatus.OK) {
					logger.debug("{}: Received HTTP {} from URL {} with id {}", configName, httpResponse.getStatusCode(), targetUrl, id);
				}

				String domainUpToSlash = targetUrl.substring(0, targetUrl.indexOf('/', 10));
				String html = httpResponse.getBody();
				html = html.replaceFirst("</head>", "<base href='"+domainUpToSlash+"/'/></head>");

				FlyoutResponse jsonWrappedHtml = new FlyoutResponse(html);
				logger.debug("JSON response is {}", wrapResponse(callback, jsonMapper.writeValueAsString(jsonWrappedHtml)));
				return new ResponseEntity<String>(wrapResponse(callback, jsonMapper.writeValueAsString(jsonWrappedHtml)), baseController.getResponseHeaders(), httpResponse.getStatusCode());
			}
			catch (NullPointerException e) {
				logger.info(configName + ": Not found when retrieving URL for id "+id, e);
				return new ResponseEntity<String>(e.toString(), baseController.getResponseHeaders(), HttpStatus.NOT_FOUND);
			}
			catch (IOException e) {
				logger.warn(configName + ": Exception retrieving URL for id "+id, e);
				return new ResponseEntity<String>(e.toString(), baseController.getResponseHeaders(), HttpStatus.INTERNAL_SERVER_ERROR);
			}
		}
		// Otherwise create something very simple from the Properties
		else {
			try {
				StringBuilder flyout = new StringBuilder();
				flyout.append("<!DOCTYPE HTML><html><body><table>\n");

				Map<String,String> doc = reconciliationService.getMatcher(configName).getRecordById(id);

				List<Property> properties = reconciliationService.getReconciliationServiceConfiguration(configName).getProperties();
				for (Property p : properties) {
					String name = p.getQueryColumnName();

					flyout.append("<tr><th>");
					flyout.append(name);
					flyout.append("</th><td>");
					flyout.append(doc.get(name));
					flyout.append("</td></tr>\n");
				}

				flyout.append("</table></body></html>\n");

				FlyoutResponse jsonWrappedHtml = new FlyoutResponse(flyout.toString());
				logger.debug("JSON response is {}", wrapResponse(callback, jsonMapper.writeValueAsString(jsonWrappedHtml)));
				return new ResponseEntity<String>(wrapResponse(callback, jsonMapper.writeValueAsString(jsonWrappedHtml)), baseController.getResponseHeaders(), HttpStatus.OK);
			}
			catch (UnknownReconciliationServiceException e) {
				return new ResponseEntity<String>(e.toString(), baseController.getResponseHeaders(), HttpStatus.NOT_FOUND);
			}
			catch (IOException e) {
				logger.warn(configName + ": Exception creating entity flyout for id "+id, e);
				return new ResponseEntity<String>(e.toString(), baseController.getResponseHeaders(), HttpStatus.INTERNAL_SERVER_ERROR);
			}
		}
	}

	/**
	 * Wrap response into JSON-P if necessary.
	 */
	private String wrapResponse(String callback, String jsonres){
		if (callback != null) {
			return callback + "(" + jsonres + ")";
		}
		else {
			return jsonres;
		}
	}

	/**
	 * Perform match query against specified configuration.
	 */
	private QueryResult[] doQuery(Query q, String configName) throws TooManyMatchesException, MatchExecutionException, UnknownReconciliationServiceException {
		ArrayList<QueryResult> qr = new ArrayList<QueryResult>();

		org.kew.rmf.refine.domain.query.Property[] properties = q.getProperties();
		// If user didn't supply any properties, try converting the query string into properties.
		if (properties == null || properties.length == 0) {
			QueryStringToPropertiesExtractor propertiesExtractor = reconciliationService.getPropertiesExtractor(configName);

			if (propertiesExtractor != null) {
				properties = propertiesExtractor.extractProperties(q.getQuery());
				logger.debug("No properties provided, parsing query «{}» into properties {}", q.getQuery(), properties);
			}
			else {
				logger.info("No properties provided, no properties resulted from parsing query string «{}»", q.getQuery());
			}
		}
		else {
			// If the user supplied some properties, but didn't supply the key property, then it comes from the query
			String keyColumnName = reconciliationService.getReconciliationServiceConfiguration(configName).getProperties().get(0).getQueryColumnName();
			if (!containsProperty(properties, keyColumnName)) {
				properties = Arrays.copyOf(properties, properties.length + 1);

				org.kew.rmf.refine.domain.query.Property keyProperty = new org.kew.rmf.refine.domain.query.Property();
				keyProperty.setP(keyColumnName);
				keyProperty.setPid(keyColumnName);
				keyProperty.setV(q.getQuery());
				logger.debug("Key property {} taken from query {}", keyColumnName, q.getQuery());

				properties[properties.length-1] = keyProperty;
			}
		}

		if (properties == null || properties.length == 0) {
			logger.info("No properties provided for query «{}», query fails", q.getQuery());
			// no query
			return null;
		}

		// Build a map by looping over each property in the config, reading its value from the
		// request object, and applying any transformations specified in the config
		Map<String, String> userSuppliedRecord = new HashMap<String, String>();
		for (org.kew.rmf.refine.domain.query.Property p : properties) {
			if (logger.isTraceEnabled()) { logger.trace("Setting: {} to {}", p.getPid(), p.getV()); }
			userSuppliedRecord.put(p.getPid(), p.getV());
		}

		List<Map<String,String>> matches = reconciliationService.doQuery(configName, userSuppliedRecord);
		logger.debug("Found {} matches", matches.size());

		for (Map<String,String> match : matches) {
			QueryResult res = new QueryResult();
			res.setId(match.get("id"));
			// Set match to true if there's only one (which allows OpenRefine to autoselect it), false otherwise
			res.setMatch(matches.size() == 1);
			// Set score to 100 * match score (which is in range 0..1)
			res.setScore(100 * Double.parseDouble(match.get(Configuration.MATCH_SCORE)));
			// Set name according to format
			res.setName(reconciliationService.getReconciliationResultFormatter(configName).formatResult(match));
			// Set type to default type
			res.setType(reconciliationService.getMetadata(configName).getDefaultTypes());
			qr.add(res);
		}

		return qr.toArray(new QueryResult[qr.size()]);
	}

	private boolean containsProperty(org.kew.rmf.refine.domain.query.Property[] properties, String property) {
		if (property == null) return false;
		for (org.kew.rmf.refine.domain.query.Property p : properties) {
			if (property.equals(p.getPid())) return true;
		}
		return false;
	}
}
