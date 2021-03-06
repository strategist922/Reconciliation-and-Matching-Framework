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
package org.kew.rmf.reconciliation.service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.PostConstruct;

import org.kew.rmf.core.configuration.MatchConfiguration;
import org.kew.rmf.core.configuration.ReconciliationServiceConfiguration;
import org.kew.rmf.core.exception.MatchExecutionException;
import org.kew.rmf.core.exception.TooManyMatchesException;
import org.kew.rmf.core.lucene.LuceneMatcher;
import org.kew.rmf.reconciliation.exception.ReconciliationServiceException;
import org.kew.rmf.reconciliation.exception.UnknownReconciliationServiceException;
import org.kew.rmf.reconciliation.queryextractor.QueryStringToPropertiesExtractor;
import org.kew.rmf.reconciliation.service.resultformatter.ReconciliationResultFormatter;
import org.kew.rmf.reconciliation.service.resultformatter.ReconciliationResultPropertyFormatter;
import org.kew.rmf.refine.domain.metadata.Metadata;
import org.perf4j.StopWatch;
import org.perf4j.aop.Profiled;
import org.perf4j.slf4j.Slf4JStopWatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.support.GenericXmlApplicationContext;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

/**
 * The ReconciliationService handles loading and using multiple reconciliation configurations.
 */
@Service
public class ReconciliationService {
	private static final Logger logger = LoggerFactory.getLogger(ReconciliationService.class);
	private static final Logger timingLogger = LoggerFactory.getLogger("org.kew.rmf.reconciliation.TimingLogger");
	private static final String queryTimingLoggerName = "org.kew.rmf.reconciliation.QueryTimingLogger";

	@Value("${environment:unknown}")
	private String environment;

	@Value("#{'${configurations}'.split(',')}")
	private List<String> initialConfigurations;

	@Autowired
	private TaskExecutor taskExecutor;

	private final Map<String,ConfigurationStatus> configurationStatuses = new HashMap<String,ConfigurationStatus>();
	public enum ConfigurationStatus {
		NOT_LOADED, LOADED, LOADING;
	}

	private final String CONFIG_BASE = "/META-INF/spring/reconciliation-service/";
	private final String CONFIG_EXTENSION = ".xml";

	private final Map<String, ConfigurableApplicationContext> contexts = new HashMap<String, ConfigurableApplicationContext>();
	private final Map<String, LuceneMatcher> matchers = new HashMap<String, LuceneMatcher>();
	private final Map<String, Integer> totals = new HashMap<String, Integer>();


	/**
	 * Kicks off tasks (threads) to load the initial configurations.
	 */
	@PostConstruct
	public void init() {
		logger.debug("Initialising reconciliation service");

		// Load up the matchers from the specified files
		if (initialConfigurations != null) {
			for (String config : initialConfigurations) {
				try {
					loadConfigurationInBackground(config + CONFIG_EXTENSION);
				}
				catch (ReconciliationServiceException e) {
					throw new RuntimeException("Error kicking off data load for Reconciliation Service", e);
				}
			}
		}
	}

	/**
	 * For loading a configuration in the background (i.e. in a thread).
	 */
	private class BackgroundConfigurationLoaderTask implements Runnable {
		private String configFileName;

		public BackgroundConfigurationLoaderTask(String configFileName) {
			this.configFileName = configFileName;
		}

		@Override
		public void run() {
			try {
				loadConfiguration(configFileName);
			}
			catch (ReconciliationServiceException e) {
				logger.error(configFileName + ": Error while loading", e);
			}
		}
	}

	/**
	 * Lists the available configuration files from the classpath.
	 */
	public List<String> listAvailableConfigurationFiles() throws ReconciliationServiceException {
		List<String> availableConfigurations = new ArrayList<>();
		ResourcePatternResolver pmrpr = new PathMatchingResourcePatternResolver();
		try {
			Resource[] configurationResources = pmrpr.getResources("classpath*:"+CONFIG_BASE+"*Match.xml");
			logger.debug("Found {} configuration file resources", configurationResources.length);

			for (Resource resource : configurationResources) {
				availableConfigurations.add(resource.getFilename());
			}
		}
		catch (IOException e) {
			throw new ReconciliationServiceException("Unable to list available configurations", e);
		}

		return availableConfigurations;
	}

	/**
	 * Loads a single configuration in the background.
	 */
	public void loadConfigurationInBackground(String configFileName) throws ReconciliationServiceException {
		synchronized (configurationStatuses) {
			ConfigurationStatus status = configurationStatuses.get(configFileName);
			if (status == ConfigurationStatus.LOADED) {
				throw new ReconciliationServiceException("Match configuration "+configFileName+" is already loaded.");
			}
			else if (status == ConfigurationStatus.LOADING) {
				throw new ReconciliationServiceException("Match configuration "+configFileName+" is loading.");
			}
			configurationStatuses.put(configFileName, ConfigurationStatus.LOADING);
		}

		taskExecutor.execute(new BackgroundConfigurationLoaderTask(configFileName));
	}

	/**
	 * Loads a single configuration.
	 */
	private void loadConfiguration(String configFileName) throws ReconciliationServiceException {
		synchronized (configurationStatuses) {
			ConfigurationStatus status = configurationStatuses.get(configFileName);
			assert (status == ConfigurationStatus.LOADING);
		}

		StopWatch sw = new Slf4JStopWatch(timingLogger);

		String configurationFile = CONFIG_BASE + configFileName;
		logger.info("{}: Loading configuration from file {}", configFileName, configurationFile);

		ConfigurableApplicationContext context = new GenericXmlApplicationContext(configurationFile);
		context.registerShutdownHook();

		LuceneMatcher matcher = context.getBean("engine", LuceneMatcher.class);
		String configName = matcher.getConfig().getName();

		contexts.put(configFileName, context);
		matchers.put(configName, matcher);

		try {
			matcher.loadData();
			totals.put(configName, matcher.getIndexReader().numDocs());
			logger.debug("{}: Loaded data", configName);

			// Append " (environment)" to Metadata name, to help with interactive testing
			Metadata metadata = getMetadata(configName);
			if (metadata != null) {
				if (!"prod".equals(environment)) {
					metadata.setName(metadata.getName() + " (" + environment + ")");
				}
			}

			synchronized (configurationStatuses) {
				ConfigurationStatus status = configurationStatuses.get(configFileName);
				if (status != ConfigurationStatus.LOADING) {
					logger.error("Unexpected configuration status '"+status+"' after loading "+configFileName);
				}
				configurationStatuses.put(configFileName, ConfigurationStatus.LOADED);
			}
		}
		catch (Exception e) {
			logger.error("Problem loading configuration "+configFileName, e);

			context.close();
			totals.remove(configName);
			matchers.remove(configName);
			contexts.remove(configFileName);

			synchronized (configurationStatuses) {
				ConfigurationStatus status = configurationStatuses.get(configFileName);
				if (status != ConfigurationStatus.LOADING) {
					logger.error("Unexpected configuration status '"+status+"' after loading "+configFileName);
				}
				configurationStatuses.remove(configFileName);
			}

			sw.stop("LoadConfiguration:"+configFileName+".failure");
			throw new ReconciliationServiceException("Problem loading configuration "+configFileName, e);
		}

		sw.stop("LoadConfiguration:"+configFileName+".success");
	}

	/**
	 * Unloads a single configuration.
	 */
	public void unloadConfiguration(String configFileName) throws ReconciliationServiceException {
		synchronized (configurationStatuses) {
			ConfigurationStatus status = configurationStatuses.get(configFileName);
			if (status == ConfigurationStatus.LOADING) {
				throw new ReconciliationServiceException("Match configuration "+configFileName+" is loading, wait until it has completed.");
			}
			else if (status == null) {
				throw new ReconciliationServiceException("Match configuration "+configFileName+" is not loaded.");
			}

			StopWatch sw = new Slf4JStopWatch(timingLogger);

			logger.info("{}: Unloading configuration", configFileName);

			ConfigurableApplicationContext context = contexts.get(configFileName);

			String configName = configFileName.substring(0, configFileName.length() - 4);
			totals.remove(configName);
			matchers.remove(configName);
			contexts.remove(configFileName);

			context.close();

			configurationStatuses.remove(configFileName);

			sw.stop("UnloadConfiguration:"+configFileName+".success");
		}
	}

	/**
	 * Retrieve reconciliation service metadata.
	 * @throws UnknownReconciliationServiceException if the requested matcher doesn't exist.
	 * @throws MatchExecutionException if no default type is specified
	 */
	public Metadata getMetadata(String configName) throws UnknownReconciliationServiceException, MatchExecutionException {
		ReconciliationServiceConfiguration reconcilationConfig = getReconciliationServiceConfiguration(configName);
		if (reconcilationConfig != null) {
			Metadata metadata = reconcilationConfig.getReconciliationServiceMetadata();
			if (metadata.getDefaultTypes() == null || metadata.getDefaultTypes().length == 0) {
				throw new MatchExecutionException("No default type specified, OpenRefine 2.6 would fail");
			}
			return metadata;
		}
		return null;
	}

	/**
	 * Convert single query string into query properties.
	 * @throws UnknownReconciliationServiceException if the requested matcher doesn't exist.
	 */
	public QueryStringToPropertiesExtractor getPropertiesExtractor(String configName) throws UnknownReconciliationServiceException {
		ReconciliationServiceConfiguration reconcilationConfig = getReconciliationServiceConfiguration(configName);
		if (reconcilationConfig != null) {
			return reconcilationConfig.getQueryStringToPropertiesExtractor();
		}
		return null;
	}

	/**
	 * Formatter to convert result into single string.
	 * @throws UnknownReconciliationServiceException if the requested matcher doesn't exist.
	 */
	public ReconciliationResultFormatter getReconciliationResultFormatter(String configName) throws UnknownReconciliationServiceException {
		ReconciliationServiceConfiguration reconcilationConfig = getReconciliationServiceConfiguration(configName);
		if (reconcilationConfig != null) {
			ReconciliationResultFormatter reconciliationResultFormatter = reconcilationConfig.getReconciliationResultFormatter();
			if (reconciliationResultFormatter != null) {
				return reconciliationResultFormatter;
			}
			else {
				// Set it to the default one
				ReconciliationResultPropertyFormatter formatter = new ReconciliationResultPropertyFormatter(reconcilationConfig);
				reconcilationConfig.setReconciliationResultFormatter(formatter);
				return formatter;
			}
		}
		return null;
	}

	/**
	 * Perform match query against specified configuration.
	 */
	@Profiled(tag="MatchQuery:{$0}", logger=queryTimingLoggerName, logFailuresSeparately=true)
	public synchronized List<Map<String,String>> doQuery(String configName, Map<String, String> userSuppliedRecord) throws TooManyMatchesException, UnknownReconciliationServiceException, MatchExecutionException {
		List<Map<String,String>> matches = null;

		LuceneMatcher matcher = getMatcher(configName);

		if (matcher == null) {
			// When no matcher specified with that configuration
			logger.warn("Invalid match configuration «{}» requested", configName);
			return null;
		}

		matches = matcher.getMatches(userSuppliedRecord);
		// Just write out some matches to std out:
		logger.debug("Found some matches: {}", matches.size());
		if (matches.size() < 4) {
			logger.debug("Matches for {} are {}", userSuppliedRecord, matches);
		}

		return matches;
	}

	/**
	 * Retrieve reconciliation service configuration.
	 * @throws UnknownReconciliationServiceException if the requested configuration doesn't exist.
	 */
	public ReconciliationServiceConfiguration getReconciliationServiceConfiguration(String configName) throws UnknownReconciliationServiceException {
		MatchConfiguration matchConfig = getMatcher(configName).getConfig();
		if (matchConfig instanceof ReconciliationServiceConfiguration) {
			ReconciliationServiceConfiguration reconcilationConfig = (ReconciliationServiceConfiguration) matchConfig;
			return reconcilationConfig;
		}
		return null;
	}

	/* • Getters and setters • */
	public Map<String, LuceneMatcher> getMatchers() {
		return matchers;
	}
	public LuceneMatcher getMatcher(String matcher) throws UnknownReconciliationServiceException {
		if (matchers.get(matcher) == null) {
			throw new UnknownReconciliationServiceException("No matcher called '"+matcher+"' exists.");
		}
		return matchers.get(matcher);
	}

	public List<String> getInitialConfigurations() {
		return initialConfigurations;
	}
	public void setInitialConfigurations(List<String> initialConfigurations) {
		this.initialConfigurations = initialConfigurations;
	}

	public Map<String, ConfigurationStatus> getConfigurationStatuses() {
		return configurationStatuses;
	}

	public Map<String, Integer> getTotals() {
		return totals;
	}
}
