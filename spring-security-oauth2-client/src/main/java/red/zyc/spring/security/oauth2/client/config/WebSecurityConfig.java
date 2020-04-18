/*
 * Copyright 2019 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package red.zyc.spring.security.oauth2.client.config;

import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpOutputMessage;
import org.springframework.http.MediaType;
import org.springframework.http.converter.FormHttpMessageConverter;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityConfigurerAdapter;
import org.springframework.security.oauth2.client.authentication.OAuth2LoginAuthenticationProvider;
import org.springframework.security.oauth2.client.endpoint.DefaultAuthorizationCodeTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.OAuth2AccessTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.OAuth2AuthorizationCodeGrantRequest;
import org.springframework.security.oauth2.client.http.OAuth2ErrorResponseErrorHandler;
import org.springframework.security.oauth2.core.endpoint.OAuth2AccessTokenResponse;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.oauth2.core.http.converter.OAuth2AccessTokenResponseHttpMessageConverter;
import org.springframework.security.web.context.SecurityContextPersistenceFilter;
import org.springframework.util.StreamUtils;
import org.springframework.web.client.RestTemplate;
import red.zyc.spring.security.oauth2.client.security.CustomizedAccessDeniedHandler;
import red.zyc.spring.security.oauth2.client.security.CustomizedAuthenticationEntryPoint;
import red.zyc.spring.security.oauth2.client.security.CustomizedOauth2UserService;
import red.zyc.spring.security.oauth2.client.security.Oauth2AuthenticationFailureHandler;
import red.zyc.spring.security.oauth2.client.security.Oauth2AuthenticationSuccessHandler;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * @author zyc
 */
@Slf4j
@Configuration
public class WebSecurityConfig extends WebSecurityConfigurerAdapter {

    public WebSecurityConfig() {
        super(true);
    }

    @Override
    protected void configure(HttpSecurity http) throws Exception {

        http
                .authorizeRequests().anyRequest().authenticated().and()

                // 通过httpSession保存认证信息
                .addFilter(new SecurityContextPersistenceFilter())

                // 配置OAuth2登录认证
                .oauth2Login(oauth2LoginConfigurer -> oauth2LoginConfigurer

                        // 认证成功后的处理器
                        .successHandler(new Oauth2AuthenticationSuccessHandler())

                        // 认证失败后的处理器
                        .failureHandler(new Oauth2AuthenticationFailureHandler())

                        // 登录请求url
                        .loginProcessingUrl("/api/login/oauth2/code/*")

                        // 配置授权服务器端点信息
                        .authorizationEndpoint(authorizationEndpointConfig -> authorizationEndpointConfig
                                // 授权端点的前缀基础url
                                .baseUri("/api/oauth2/authorization"))
                        // 配置获取access_token的端点信息
                        .tokenEndpoint(tokenEndpointConfig -> tokenEndpointConfig.accessTokenResponseClient(oAuth2AccessTokenResponseClient()))

                        // 配置获取userInfo的端点信息
                        .userInfoEndpoint(userInfoEndpointConfig -> userInfoEndpointConfig.userService(new CustomizedOauth2UserService()))
                )

                // 配置匿名用户过滤器
                .anonymous().and()

                // 配置认证端点和未授权的请求处理器
                .exceptionHandling(exceptionHandlingConfigurer -> exceptionHandlingConfigurer
                        .authenticationEntryPoint(new CustomizedAuthenticationEntryPoint())
                        .accessDeniedHandler(new CustomizedAccessDeniedHandler()));

    }

    /**
     * qq获取access_token返回的结果是类似get请求参数的字符串，无法通过指定Accept请求头来使qq返回特定的响应类型，并且qq返回的access_token
     * 也缺少了必须的token_type字段（不符合oauth2标准的授权码认证流程），spring-security默认远程获取
     * access_token的客户端是{@link DefaultAuthorizationCodeTokenResponseClient}，所以我们需要
     * 自定义{@link QqoAuth2AccessTokenResponseHttpMessageConverter}注入到这个client中来解析qq的access_token响应信息
     *
     * @return {@link DefaultAuthorizationCodeTokenResponseClient} 用来获取access_token的客户端
     * @see <a href="https://www.oauth.com/oauth2-servers/access-tokens/authorization-code-request">authorization-code-request规范</a>
     * @see <a href="https://www.oauth.com/oauth2-servers/access-tokens/access-token-response">access-token-response规范</a>
     * @see <a href="https://wiki.connect.qq.com/%E5%BC%80%E5%8F%91%E6%94%BB%E7%95%A5_server-side">qq开发文档</a>
     */
    private OAuth2AccessTokenResponseClient<OAuth2AuthorizationCodeGrantRequest> oAuth2AccessTokenResponseClient() {
        DefaultAuthorizationCodeTokenResponseClient client = new DefaultAuthorizationCodeTokenResponseClient();
        RestTemplate restTemplate = new RestTemplate(Arrays.asList(
                new FormHttpMessageConverter(),

                // 解析标准的AccessToken响应信息转换器
                new OAuth2AccessTokenResponseHttpMessageConverter(),

                // 解析qq的AccessToken响应信息转换器
                new QqoAuth2AccessTokenResponseHttpMessageConverter(MediaType.TEXT_HTML)));
        restTemplate.setErrorHandler(new OAuth2ErrorResponseErrorHandler());
        client.setRestOperations(restTemplate);
        return client;
    }

    /**
     * 自定义消息转换器来解析qq的access_token响应信息
     *
     * @see OAuth2AccessTokenResponseHttpMessageConverter#readInternal(java.lang.Class, org.springframework.http.HttpInputMessage)
     * @see OAuth2LoginAuthenticationProvider#authenticate(org.springframework.security.core.Authentication)
     */
    private static class QqoAuth2AccessTokenResponseHttpMessageConverter extends OAuth2AccessTokenResponseHttpMessageConverter {

        public QqoAuth2AccessTokenResponseHttpMessageConverter(MediaType... mediaType) {
            setSupportedMediaTypes(Arrays.asList(mediaType));
        }

        @SneakyThrows
        @Override
        protected OAuth2AccessTokenResponse readInternal(Class<? extends OAuth2AccessTokenResponse> clazz, HttpInputMessage inputMessage) {

            String response = StreamUtils.copyToString(inputMessage.getBody(), StandardCharsets.UTF_8);

            log.info("qq的AccessToken响应信息：{}", response);

            // 解析响应信息类似access_token=YOUR_ACCESS_TOKEN&expires_in=3600这样的字符串
            Map<String, String> tokenResponseParameters = Arrays.stream(response.split("&")).collect(Collectors.toMap(s -> s.split("=")[0], s -> s.split("=")[1]));

            // 手动给qq的access_token响应信息添加token_type字段，spring-security会按照oauth2规范校验返回参数
            tokenResponseParameters.put(OAuth2ParameterNames.TOKEN_TYPE, "bearer");
            return this.tokenResponseConverter.convert(tokenResponseParameters);
        }

        @Override
        protected void writeInternal(OAuth2AccessTokenResponse tokenResponse, HttpOutputMessage outputMessage) {
            throw new UnsupportedOperationException();
        }
    }


}
