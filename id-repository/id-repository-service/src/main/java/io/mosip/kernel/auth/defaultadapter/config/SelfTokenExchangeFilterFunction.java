package io.mosip.kernel.auth.defaultadapter.config;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;

import io.mosip.kernel.auth.defaultadapter.constant.AuthAdapterConstant;
import io.mosip.kernel.auth.defaultadapter.constant.AuthAdapterErrorCode;
import io.mosip.kernel.auth.defaultadapter.exception.AuthAdapterException;
import io.mosip.kernel.auth.defaultadapter.helper.TokenHelper;
import io.mosip.kernel.auth.defaultadapter.helper.TokenValidationHelper;
import io.mosip.kernel.auth.defaultadapter.model.TokenHolder;
import reactor.core.publisher.Mono;

/**
 * Spring Framework 7 shadow of kernel-auth {@code SelfTokenExchangeFilterFunction} (v1.3.1).
 * API changes vs openid-bridge: {@code getOrEmpty} and rebuild {@link ClientRequest} (headers are read-only).
 */
public class SelfTokenExchangeFilterFunction implements ExchangeFilterFunction {

	private static final Logger LOGGER = LoggerFactory.getLogger(SelfTokenExchangeFilterFunction.class);

	private String clientID;

	private String clientSecret;

	private String appID;

	private TokenHolder cachedToken;

	private TokenHelper tokenHelper;

	private TokenValidationHelper tokenValidationHelper;

	private WebClient webClient;

	public SelfTokenExchangeFilterFunction(Environment environment, WebClient webClient, TokenHolder cachedToken,
			TokenHelper tokenHelper, TokenValidationHelper tokenValidationHelper, String applName) {
		clientID = environment.getProperty("mosip.iam.adapter.clientid." + applName,
				environment.getProperty("mosip.iam.adapter.clientid", ""));
		clientSecret = environment.getProperty("mosip.iam.adapter.clientsecret." + applName,
				environment.getProperty("mosip.iam.adapter.clientsecret", ""));
		appID = environment.getProperty("mosip.iam.adapter.appid." + applName,
				environment.getProperty("mosip.iam.adapter.appid", ""));
		this.cachedToken = cachedToken;
		this.webClient = webClient;
		this.tokenHelper = tokenHelper;
		this.tokenValidationHelper = tokenValidationHelper;
	}

	@Override
	public Mono<ClientResponse> filter(ClientRequest request, ExchangeFunction next) {
		if (cachedToken.getToken() == null) {
			String authToken = tokenHelper.getClientToken(clientID, clientSecret, appID, webClient);
			if (Objects.isNull(authToken)) {
				LOGGER.error("there is some issue with getting token with clienid and secret");
				throw new AuthAdapterException(AuthAdapterErrorCode.SELF_AUTH_TOKEN_NULL.getErrorCode(),
						AuthAdapterErrorCode.SELF_AUTH_TOKEN_NULL.getErrorMessage());
			}
			cachedToken.setToken(authToken);
		}

		ClientRequest newReq = ClientRequest.from(request)
				.header(AuthAdapterConstant.AUTH_HEADER_COOKIE,
						AuthAdapterConstant.AUTH_HEADER + cachedToken.getToken())
				.build();
		ClientResponse response = next.exchange(newReq).block();
		if (response != null && response.statusCode() != HttpStatus.UNAUTHORIZED) {
			return Mono.just(response);
		}

		synchronized (this) {
			if (!isTokenValid((String) cachedToken.getToken())) {
				String authToken = tokenHelper.getClientToken(clientID, clientSecret, appID, webClient);
				cachedToken.setToken(authToken);
			}
		}

		List<String> cookies = request.headers().getOrEmpty(AuthAdapterConstant.AUTH_HEADER_COOKIE).stream()
				.filter(str -> !str.contains(AuthAdapterConstant.AUTH_HEADER))
				.collect(Collectors.toList());
		ClientRequest.Builder retryBuilder = ClientRequest.from(request);
		retryBuilder.headers(headers -> {
			headers.remove(AuthAdapterConstant.AUTH_HEADER_COOKIE);
			cookies.forEach(cookie -> headers.add(AuthAdapterConstant.AUTH_HEADER_COOKIE, cookie));
			headers.add(AuthAdapterConstant.AUTH_HEADER_COOKIE,
					AuthAdapterConstant.AUTH_HEADER + cachedToken.getToken());
		});
		return next.exchange(retryBuilder.build());
	}

	private boolean isTokenValid(String authToken) {
		return Objects.nonNull(tokenValidationHelper.doOnlineTokenValidation(authToken, webClient));
	}
}