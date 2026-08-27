package io.mosip.kernel.auth.defaultadapter.config;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.RestTemplate;

import io.mosip.kernel.auth.defaultadapter.constant.AuthAdapterConstant;
import io.mosip.kernel.auth.defaultadapter.constant.AuthAdapterErrorCode;
import io.mosip.kernel.auth.defaultadapter.exception.AuthAdapterException;
import io.mosip.kernel.auth.defaultadapter.helper.TokenHelper;
import io.mosip.kernel.auth.defaultadapter.helper.TokenValidationHelper;
import io.mosip.kernel.auth.defaultadapter.model.TokenHolder;

/**
 * Spring Framework 7 shadow of kernel-auth {@code SelfTokenRestInterceptor} (v1.3.1).
 * API change vs openid-bridge: {@code HttpHeaders.get(Object)} / {@code replace} → {@code getOrEmpty} / remove-add.
 */
public class SelfTokenRestInterceptor implements ClientHttpRequestInterceptor {

	private static final Logger LOGGER = LoggerFactory.getLogger(SelfTokenRestInterceptor.class);

	private String clientID;

	private String clientSecret;

	private String appID;

	private TokenHolder<String> cachedToken;

	private RestTemplate restTemplate;

	private TokenHelper tokenHelper;

	private TokenValidationHelper tokenValidationHelper;

	public SelfTokenRestInterceptor(Environment environment, RestTemplate restTemplate,
			TokenHolder<String> cachedToken, TokenHelper tokenHelper, TokenValidationHelper tokenValidationHelper,
			String applName) {
		clientID = environment.getProperty("mosip.iam.adapter.clientid." + applName,
				environment.getProperty("mosip.iam.adapter.clientid", ""));
		clientSecret = environment.getProperty("mosip.iam.adapter.clientsecret." + applName,
				environment.getProperty("mosip.iam.adapter.clientsecret", ""));
		appID = environment.getProperty("mosip.iam.adapter.appid." + applName,
				environment.getProperty("mosip.iam.adapter.appid", ""));
		this.cachedToken = cachedToken;
		this.restTemplate = restTemplate;
		this.tokenHelper = tokenHelper;
		this.tokenValidationHelper = tokenValidationHelper;
	}

	@Override
	public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
			throws IOException {
		if (cachedToken.getToken() == null) {
			String authToken = tokenHelper.getClientToken(clientID, clientSecret, appID, restTemplate);
			if (Objects.isNull(authToken)) {
				LOGGER.error("there is some issue with getting token with clienid and secret");
				throw new AuthAdapterException(AuthAdapterErrorCode.SELF_AUTH_TOKEN_NULL.getErrorCode(),
						AuthAdapterErrorCode.SELF_AUTH_TOKEN_NULL.getErrorMessage());
			}
			cachedToken.setToken(authToken);
		}
		request.getHeaders().add(AuthAdapterConstant.AUTH_HEADER_COOKIE,
				AuthAdapterConstant.AUTH_HEADER + cachedToken.getToken());

		ClientHttpResponse clientHttpResponse = execution.execute(request, body);
		if (clientHttpResponse.getStatusCode() != HttpStatus.UNAUTHORIZED) {
			return clientHttpResponse;
		}

		synchronized (this) {
			if (!isTokenValid(cachedToken.getToken())) {
				String authToken = tokenHelper.getClientToken(clientID, clientSecret, appID, restTemplate);
				cachedToken.setToken(authToken);
			}
		}

		List<String> cookies = request.getHeaders().getOrEmpty(AuthAdapterConstant.AUTH_HEADER_COOKIE).stream()
				.filter(str -> !str.contains(AuthAdapterConstant.AUTH_HEADER))
				.collect(Collectors.toList());
		request.getHeaders().remove(AuthAdapterConstant.AUTH_HEADER_COOKIE);
		cookies.forEach(cookie -> request.getHeaders().add(AuthAdapterConstant.AUTH_HEADER_COOKIE, cookie));
		request.getHeaders().add(AuthAdapterConstant.AUTH_HEADER_COOKIE,
				AuthAdapterConstant.AUTH_HEADER + cachedToken.getToken());
		return execution.execute(request, body);
	}
	private boolean isTokenValid(String authToken) {
		return Objects.nonNull(tokenValidationHelper.getOnlineTokenValidatedUserResponse(authToken, restTemplate));
	}
}
