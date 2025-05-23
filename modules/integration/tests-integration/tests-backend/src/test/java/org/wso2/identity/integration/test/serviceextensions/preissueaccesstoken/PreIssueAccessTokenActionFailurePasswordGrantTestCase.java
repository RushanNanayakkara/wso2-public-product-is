/*
 * Copyright (c) 2024, WSO2 LLC. (http://www.wso2.com).
 *
 * WSO2 LLC. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.identity.integration.test.serviceextensions.preissueaccesstoken;

import org.apache.http.Header;
import org.apache.http.HttpResponse;
import org.apache.http.NameValuePair;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.message.BasicHeader;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.util.EntityUtils;
import org.json.JSONObject;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Factory;
import org.testng.annotations.Test;
import org.wso2.carbon.automation.engine.context.TestUserMode;
import org.wso2.identity.integration.test.serviceextensions.common.ActionsBaseTestCase;
import org.wso2.identity.integration.test.serviceextensions.dataprovider.model.ActionResponse;
import org.wso2.identity.integration.test.serviceextensions.dataprovider.model.ExpectedTokenResponse;
import org.wso2.identity.integration.test.serviceextensions.mockservices.ServiceExtensionMockServer;
import org.wso2.identity.integration.test.rest.api.server.action.management.v1.common.model.ActionModel;
import org.wso2.identity.integration.test.rest.api.server.action.management.v1.common.model.AuthenticationType;
import org.wso2.identity.integration.test.rest.api.server.action.management.v1.common.model.Endpoint;
import org.wso2.identity.integration.test.rest.api.server.application.management.v1.model.ApplicationResponseModel;
import org.wso2.identity.integration.test.rest.api.server.application.management.v1.model.OpenIDConnectConfiguration;
import org.wso2.identity.integration.test.rest.api.user.common.model.Email;
import org.wso2.identity.integration.test.rest.api.user.common.model.Name;
import org.wso2.identity.integration.test.rest.api.user.common.model.UserObject;
import org.wso2.identity.integration.test.restclients.SCIM2RestClient;
import org.wso2.identity.integration.test.utils.FileUtils;
import org.wso2.identity.integration.test.utils.OAuth2Constant;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.wso2.identity.integration.test.utils.OAuth2Constant.ACCESS_TOKEN_ENDPOINT;
import static org.wso2.identity.integration.test.utils.OAuth2Constant.AUTHORIZATION_HEADER;

/**
 * Tests the pre-issue access token action failure scenarios with password grant type.
 */
public class PreIssueAccessTokenActionFailurePasswordGrantTestCase extends ActionsBaseTestCase {

    private static final String TEST_USER = "test_user";
    private static final String TEST_WSO2 = "Test@wso2";
    private static final String PRE_ISSUE_ACCESS_TOKEN_API_PATH = "preIssueAccessToken";
    private static final String MOCK_SERVER_ENDPOINT_RESOURCE_PATH = "/test/action";
    private static final String PASSWORD_GRANT_TYPE = "password";
    private CloseableHttpClient client;
    private SCIM2RestClient scim2RestClient;
    private List<String> requestedScopes;
    private String clientId;
    private String clientSecret;
    private String actionId;
    private String applicationId;
    private String userId;
    private final TestUserMode userMode;
    private ServiceExtensionMockServer serviceExtensionMockServer;
    private final ActionResponse actionResponse;
    private final ExpectedTokenResponse expectedTokenResponse;

    @Factory(dataProvider = "testExecutionContextProvider")
    public PreIssueAccessTokenActionFailurePasswordGrantTestCase(TestUserMode testUserMode,
                                                                 ActionResponse actionResponse,
                                                                 ExpectedTokenResponse expectedTokenResponse) {

        this.userMode = testUserMode;
        this.actionResponse = actionResponse;
        this.expectedTokenResponse = expectedTokenResponse;
    }

    @DataProvider(name = "testExecutionContextProvider")
    public static Object[][] getTestExecutionContext() throws Exception {

        return new Object[][]{
                {TestUserMode.SUPER_TENANT_USER, new ActionResponse(200,
                        FileUtils.readFileInClassPathAsString("actions/response/incomplete-response.json")),
                        new ExpectedTokenResponse(500, "server_error", "Internal Server Error.")},
                {TestUserMode.SUPER_TENANT_USER, new ActionResponse(200,
                        FileUtils.readFileInClassPathAsString("actions/response/failure-response.json")),
                        new ExpectedTokenResponse(400, "Some failure reason", "Some description")},
                {TestUserMode.TENANT_USER, new ActionResponse(200,
                        FileUtils.readFileInClassPathAsString("actions/response/failure-response.json")),
                        new ExpectedTokenResponse(400, "Some failure reason", "Some description")},
                {TestUserMode.TENANT_USER, new ActionResponse(500,
                        FileUtils.readFileInClassPathAsString("actions/response/error-response.json")),
                        new ExpectedTokenResponse(500, "server_error", "Internal Server Error.")},
                {TestUserMode.TENANT_USER, new ActionResponse(401, "Unauthorized"),
                        new ExpectedTokenResponse(500, "server_error", "Internal Server Error.")},
        };
    }

    @BeforeClass(alwaysRun = true)
    public void testInit() throws Exception {

        super.init(userMode);
        client = HttpClientBuilder.create().build();

        scim2RestClient = new SCIM2RestClient(serverURL, tenantInfo);
        ApplicationResponseModel application = addApplicationWithGrantType(PASSWORD_GRANT_TYPE);
        applicationId = application.getId();
        OpenIDConnectConfiguration oidcConfig = getOIDCInboundDetailsOfApplication(applicationId);
        clientId = oidcConfig.getClientId();
        clientSecret = oidcConfig.getClientSecret();
        actionId = createPreIssueAccessTokenAction();

        addUser();

        requestedScopes = new ArrayList<>(Arrays.asList("openid", "profile"));

        serviceExtensionMockServer = new ServiceExtensionMockServer();
        serviceExtensionMockServer.startServer();
        serviceExtensionMockServer.setupStub(MOCK_SERVER_ENDPOINT_RESOURCE_PATH,
                "Basic " + getBase64EncodedString(MOCK_SERVER_AUTH_BASIC_USERNAME, MOCK_SERVER_AUTH_BASIC_PASSWORD),
                actionResponse.getResponseBody(), actionResponse.getStatusCode());
    }

    @AfterClass(alwaysRun = true)
    public void atEnd() throws Exception {

        serviceExtensionMockServer.stopServer();

        deleteAction(PRE_ISSUE_ACCESS_TOKEN_API_PATH, actionId);
        deleteApp(applicationId);
        scim2RestClient.deleteUser(userId);

        restClient.closeHttpClient();
        scim2RestClient.closeHttpClient();
        actionsRestClient.closeHttpClient();
        client.close();

        serviceExtensionMockServer = null;
    }

    @Test(groups = "wso2.is", description = "Verify token response when pre-issue access token action fails with " +
            "password grant type.")
    public void testPreIssueAccessTokenActionFailure() throws Exception {

        HttpResponse response = sendTokenRequestForPasswordGrant();

        assertNotNull(response);
        assertEquals(response.getStatusLine().getStatusCode(), expectedTokenResponse.getStatusCode());

        String responseString = EntityUtils.toString(response.getEntity(), "UTF-8");
        JSONObject jsonResponse = new JSONObject(responseString);
        assertEquals(jsonResponse.getString("error"), expectedTokenResponse.getErrorMessage());
        assertEquals(jsonResponse.getString("error_description"), expectedTokenResponse.getErrorDescription());
    }

    private HttpResponse sendTokenRequestForPasswordGrant() throws Exception {

        List<NameValuePair> parameters = new ArrayList<>();
        parameters.add(new BasicNameValuePair("grant_type", OAuth2Constant.OAUTH2_GRANT_TYPE_RESOURCE_OWNER));
        parameters.add(new BasicNameValuePair("username", TEST_USER));
        parameters.add(new BasicNameValuePair("password", TEST_WSO2));

        String scopes = String.join(" ", requestedScopes);
        parameters.add(new BasicNameValuePair("scope", scopes));

        List<Header> headers = new ArrayList<>();
        headers.add(new BasicHeader(AUTHORIZATION_HEADER, OAuth2Constant.BASIC_HEADER + " " +
                getBase64EncodedString(clientId, clientSecret)));
        headers.add(new BasicHeader("Content-Type", "application/x-www-form-urlencoded"));
        headers.add(new BasicHeader("User-Agent", OAuth2Constant.USER_AGENT));

        return sendPostRequest(client, headers, parameters,
                getTenantQualifiedURL(ACCESS_TOKEN_ENDPOINT, tenantInfo.getDomain()));
    }

    private String createPreIssueAccessTokenAction() throws IOException {

        AuthenticationType authenticationType = new AuthenticationType();
        authenticationType.setType(AuthenticationType.TypeEnum.BASIC);
        Map<String, Object> authProperties = new HashMap<>();
        authProperties.put(USERNAME_PROPERTY, MOCK_SERVER_AUTH_BASIC_USERNAME);
        authProperties.put(PASSWORD_PROPERTY, MOCK_SERVER_AUTH_BASIC_PASSWORD);
        authenticationType.setProperties(authProperties);

        Endpoint endpoint = new Endpoint();
        endpoint.setUri(EXTERNAL_SERVICE_URI);
        endpoint.setAuthentication(authenticationType);

        ActionModel actionModel = new ActionModel();
        actionModel.setName("Access Token Pre Issue");
        actionModel.setDescription("This is a test pre issue access token type");
        actionModel.setEndpoint(endpoint);

        return createAction(PRE_ISSUE_ACCESS_TOKEN_API_PATH, actionModel);
    }

    private void addUser() throws Exception {

        UserObject userInfo = new UserObject();
        userInfo.setUserName(TEST_USER);
        userInfo.setPassword(TEST_WSO2);
        userInfo.setName(new Name().givenName("test_user_given_name"));
        userInfo.getName().setFamilyName("test_user_last_name");
        userInfo.addEmail(new Email().value("test.user@gmail.com"));
        userId = scim2RestClient.createUser(userInfo);
    }
}
