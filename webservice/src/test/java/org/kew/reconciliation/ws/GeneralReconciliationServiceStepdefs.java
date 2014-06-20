package org.kew.reconciliation.ws;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.codehaus.jackson.map.ObjectMapper;
import org.hamcrest.Matchers;
import org.junit.Assert;
import org.kew.reconciliation.refine.domain.metadata.Metadata;
import org.skyscreamer.jsonassert.JSONAssert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurationSupport;

import cucumber.api.java.Before;
import cucumber.api.java.en.Then;
import cucumber.api.java.en.When;

@WebAppConfiguration
@ContextConfiguration("classpath:cucumber.xml")
public class GeneralReconciliationServiceStepdefs extends WebMvcConfigurationSupport {
	private static Logger log = LoggerFactory.getLogger(GeneralReconciliationServiceStepdefs.class);

	@Autowired
	private WebApplicationContext wac;

	private ObjectMapper mapper = new ObjectMapper();
	private MockMvc mockMvc;

	private String responseJson;
	private Metadata responseMetadata;

	private MvcResult result;

	@Before
	public void setup() {
		mockMvc = MockMvcBuilders.webAppContextSetup(wac).build();
	}

	@When("^I query for reconciliation service metadata$")
	public void I_query_for_reconciliation_service_metadata() throws Throwable {
		// Call
		result = mockMvc.perform(get("/reconcile/generalTest").accept(MediaType.parseMediaType("application/json;charset=UTF-8")))
				.andExpect(status().isOk())
				.andExpect(content().contentType("application/json;charset=UTF-8"))
				.andReturn();

		String msg = result.getResponse().getContentAsString();
		log.debug("Response as string was {}", msg);
		responseMetadata = mapper.readValue(msg, Metadata.class);

		// Check response
		log.info("Received response {}", responseMetadata);
	}

	@Then("^I receive the following metadata response:$")
	public void i_receive_the_following_metadata_response(String expectedResponseString) throws Throwable {
		Metadata expectedResponse = new ObjectMapper().readValue(expectedResponseString, Metadata.class);
		Assert.assertThat("Metadata response correct", responseMetadata, Matchers.equalTo(expectedResponse));
	}

	@When("^I make a match query for \"(.*?)\"$")
	public void i_make_a_match_query_for(String queryString) throws Throwable {
		// Call
		result = mockMvc.perform(get("/match/generalTest?"+queryString).accept(MediaType.parseMediaType("application/json;charset=UTF-8")))
				.andExpect(status().isOk())
				.andExpect(content().contentType("application/json;charset=UTF-8"))
				.andReturn();

		responseJson = result.getResponse().getContentAsString();
		log.debug("Response as string was {}", responseJson);
	}

	@Then("^I receive the following match response:$")
	public void i_receive_the_following_match_response(String expectedJson) throws Throwable {
		JSONAssert.assertEquals(expectedJson, responseJson, true);
	}
}