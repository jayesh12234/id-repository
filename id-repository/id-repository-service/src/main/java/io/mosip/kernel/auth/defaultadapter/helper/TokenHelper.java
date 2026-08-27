package io.mosip.kernel.auth.defaultadapter.helper;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.mosip.kernel.auth.defaultadapter.constant.AuthAdapterConstant;
import io.mosip.kernel.auth.defaultadapter.constant.AuthAdapterErrorCode;
import io.mosip.kernel.auth.defaultadapter.exception.AuthRestException;
import io.mosip.kernel.core.exception.ExceptionUtils;
import io.mosip.kernel.core.exception.ServiceError;

/**
 * Spring Framework 7 / Boot 4 shadow of kernel-auth {@code TokenHelper} (v1.3.1).
 * API changes vs openid-bridge: {@code WebClient.exchange()} → {@code retrieve()};
 * WebClient body decoded as {@code String} then Jackson 2 (Boot 4 codecs are Jackson 3).
 */
public class TokenHelper {

	private static final Logger LOGGER = LoggerFactory.getLogger(TokenHelper.class);

	@Value("${auth.server.admin.issuer.uri:}")
	private String issuerURI;

	@Value("${auth.server.admin.issuer.internal.uri:}")
	private String issuerInternalURI;

	@Autowired
	private ObjectMapper mapper;

	@Value("#{${mosip.kernel.auth.appids.realm.map}}")
	private Map<String, String> realmMap;

	@Value("${auth.server.admin.oidc.token.path:/protocol/openid-connect/token}")
	private String tokenPath;

	public String getClientToken(String clientId, String clientSecret, String appId, RestTemplate restTemplate) {
		if ("".equals(issuerURI)) {
			LOGGER.warn("OIDC Service URL is not available in config file, not requesting for new auth token.");
			return null;
		}
		issuerInternalURI = issuerInternalURI.trim().isEmpty() ? issuerURI : issuerInternalURI;
		LOGGER.info("Requesting for new Token for the provided OIDC Service: {}", issuerInternalURI);
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

		MultiValueMap<String, String> valueMap = new LinkedMultiValueMap<>();
		valueMap.add(AuthAdapterConstant.GRANT_TYPE, AuthAdapterConstant.CLIENT_CREDENTIALS);
		valueMap.add(AuthAdapterConstant.CLIENT_ID, clientId);
		valueMap.add(AuthAdapterConstant.CLIENT_SECRET, clientSecret);

		ResponseEntity<String> response = null;
		try {
			HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(valueMap, headers);
			String realm = getRealmIdFromAppId(appId);
			if (Objects.isNull(realm)) {
				return null;
			}
			String tokenUrl = new StringBuilder(issuerInternalURI).append(realm).append(tokenPath).toString();
			response = restTemplate.postForEntity(tokenUrl, request, String.class);
		}
		catch (HttpServerErrorException | HttpClientErrorException e) {
			LOGGER.error("error connecting to keycloak {}", e.getResponseBodyAsString());
		}
		if (response == null) {
			LOGGER.error("error connecting to keycloak {}",
					AuthAdapterErrorCode.CANNOT_CONNECT_TO_AUTH_SERVICE.getErrorMessage());
			return null;
		}
		String responseBody = response.getBody();
		List<ServiceError> validationErrorList = ExceptionUtils.getServiceErrorList(responseBody);
		if (!validationErrorList.isEmpty()) {
			throw new AuthRestException(validationErrorList);
		}
		try {
			JsonNode jsonNode = mapper.readTree(responseBody);
			String accessToken = jsonNode.get(AuthAdapterConstant.ACCESS_TOKEN).asText();
			if (Objects.nonNull(accessToken)) {
				LOGGER.info("Found Token in response body and returning the Token");
				return accessToken;
			}
		}
		catch (IOException e) {
			LOGGER.error("Error Parsing Response data {}", e.getMessage(), e);
		}

		LOGGER.error("Error connecting to OIDC service (RestTemplate) {} or UNKNOWN Error.",
				AuthAdapterErrorCode.CANNOT_CONNECT_TO_AUTH_SERVICE.getErrorMessage());
		return null;
	}
	public String getClientToken(String clientId, String clientSecret, String appId, WebClient webClient) {
		if ("".equals(issuerURI)) {
			LOGGER.warn("OIDC Service URL is not available in config file, not requesting for new auth token.");
			return null;
		}
		issuerInternalURI = issuerInternalURI.trim().isEmpty() ? issuerURI : issuerInternalURI;
		LOGGER.info("Requesting for new Token for the provided OIDC Service(WebClient): {}", issuerInternalURI);
		MultiValueMap<String, String> valueMap = new LinkedMultiValueMap<>();
		valueMap.add(AuthAdapterConstant.GRANT_TYPE, AuthAdapterConstant.CLIENT_CREDENTIALS);
		valueMap.add(AuthAdapterConstant.CLIENT_ID, clientId);
		valueMap.add(AuthAdapterConstant.CLIENT_SECRET, clientSecret);

		String realm = getRealmIdFromAppId(appId);
		if (Objects.isNull(realm)) {
			return null;
		}
		String tokenUrl = new StringBuilder(issuerInternalURI).append(realm).append(tokenPath).toString();
		try {
			String responseBody = webClient.method(HttpMethod.POST)
					.uri(UriComponentsBuilder.fromUriString(tokenUrl).toUriString())
					.contentType(MediaType.APPLICATION_FORM_URLENCODED)
					.body(BodyInserters.fromFormData(valueMap))
					.retrieve()
					.bodyToMono(String.class)
					.block();
			String accessToken = null;
			if (responseBody != null) {
				JsonNode jsonNode = mapper.readTree(responseBody);
				JsonNode tokenNode = jsonNode.get(AuthAdapterConstant.ACCESS_TOKEN);
				if (tokenNode != null && !tokenNode.isNull()) {
					accessToken = tokenNode.asText();
				}
			}
			if (Objects.nonNull(accessToken)) {
				LOGGER.info("Found Token in response body and returning the Token(WebClient)");
				return accessToken;
			}
		}
		catch (WebClientResponseException e) {
			// Spring 7 retrieve() throws on non-2xx; kernel-auth treated that as connect error
		}
		catch (IOException e) {
			LOGGER.error("Error Parsing Response data {}", e.getMessage(), e);
		}

		LOGGER.error("Error connecting to OIDC service (WebClient) {} or UNKNOWN Error.",
				AuthAdapterErrorCode.CANNOT_CONNECT_TO_AUTH_SERVICE.getErrorMessage());
		return null;
	}
	private String getRealmIdFromAppId(String appId) {
		if (realmMap.get(appId) != null) {
			return realmMap.get(appId).toLowerCase();
		}

		LOGGER.warn(
				"Realm not configured in configuration for appId: " + appId + ", not requesting for new auth token.");
		return null;
	}
}