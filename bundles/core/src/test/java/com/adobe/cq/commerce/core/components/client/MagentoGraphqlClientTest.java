/*******************************************************************************
 *
 *    Copyright 2019 Adobe. All rights reserved.
 *    This file is licensed to you under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License. You may obtain a copy
 *    of the License at http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software distributed under
 *    the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR REPRESENTATIONS
 *    OF ANY KIND, either express or implied. See the License for the specific language
 *    governing permissions and limitations under the License.
 *
 ******************************************************************************/

package com.adobe.cq.commerce.core.components.client;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Dictionary;
import java.util.Hashtable;
import java.util.List;

import org.apache.http.Header;
import org.apache.http.message.BasicHeader;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.testing.mock.caconfig.ContextPlugins;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.ArgumentMatcher;
import org.mockito.Mockito;
import org.osgi.service.cm.Configuration;
import org.osgi.service.cm.ConfigurationAdmin;

import com.adobe.cq.commerce.graphql.client.GraphqlClient;
import com.adobe.cq.commerce.graphql.client.HttpMethod;
import com.adobe.cq.commerce.graphql.client.RequestOptions;
import com.adobe.cq.commerce.magento.graphql.gson.QueryDeserializer;
import com.adobe.cq.launches.api.Launch;
import com.day.cq.commons.jcr.JcrConstants;
import com.day.cq.wcm.api.Page;
import com.google.common.base.Function;
import io.wcm.testing.mock.aem.junit.AemContext;
import io.wcm.testing.mock.aem.junit.AemContextBuilder;

public class MagentoGraphqlClientTest {

    private GraphqlClient graphqlClient;

    @Rule
    public final AemContext context = new AemContextBuilder(ResourceResolverType.JCR_MOCK).plugin(ContextPlugins.CACONFIG)
        .beforeSetUp(context -> {
            ConfigurationAdmin configurationAdmin = context.getService(ConfigurationAdmin.class);
            Configuration serviceConfiguration = configurationAdmin.getConfiguration(
                "org.apache.sling.caconfig.resource.impl.def.DefaultContextPathStrategy");

            Dictionary<String, Object> props = new Hashtable<>();
            props.put("configRefResourceNames", new String[] { ".", "jcr:content" });
            props.put("configRefPropertyNames", "cq:conf");
            serviceConfiguration.update(props);

            serviceConfiguration = configurationAdmin.getConfiguration(
                "org.apache.sling.caconfig.resource.impl.def.DefaultConfigurationResourceResolvingStrategy");
            props = new Hashtable<>();
            props.put("configPath", "/conf");
            serviceConfiguration.update(props);

            serviceConfiguration = configurationAdmin.getConfiguration("org.apache.sling.caconfig.impl.ConfigurationResolverImpl");
            props = new Hashtable<>();
            props.put("configBucketNames", new String[] { "settings" });
            serviceConfiguration.update(props);
        }).build();

    @Before
    public void setup() throws IOException {

        context.load()
            .json("/context/jcr-content.json", "/content");
        context.load()
            .json("/context/jcr-conf.json", "/conf/test-config");
        graphqlClient = Mockito.mock(GraphqlClient.class);
        Mockito.when(graphqlClient.execute(Mockito.any(), Mockito.any(), Mockito.any()))
            .thenReturn(null);
    }

    private void testMagentoStoreProperty(Resource resource, boolean withStoreHeader) {
        Mockito.when(resource.adaptTo(GraphqlClient.class))
            .thenReturn(graphqlClient);

        MagentoGraphqlClient client = MagentoGraphqlClient.create(resource);
        executeAndCheck(withStoreHeader, client, null);
    }

    private void executeAndCheck(boolean withStoreHeader, MagentoGraphqlClient client, String previewVersion) {
        // Verify parameters with default execute() method and store property
        client.execute("{dummy}");
        List<Header> headers = new ArrayList<>();
        if (withStoreHeader) {
            headers.add(new BasicHeader("Store", "my-store"));
        }
        if (previewVersion != null) {
            headers.add(new BasicHeader("Preview-Version", previewVersion));
        }
        RequestOptionsMatcher matcher = new RequestOptionsMatcher(headers, null);
        Mockito.verify(graphqlClient)
            .execute(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.argThat(matcher));

        // Verify setting a custom HTTP method
        client.execute("{dummy}", HttpMethod.GET);
        matcher = new RequestOptionsMatcher(headers, HttpMethod.GET);
        Mockito.verify(graphqlClient)
            .execute(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.argThat(matcher));
    }

    @Test
    public void testMagentoStorePropertyWithConfigBuilder() {
        /*
         * The content for this test looks slightly different than it does in AEM:
         * In AEM there the tree structure is /conf/<config>/settings/cloudconfigs/commerce/jcr:content
         * In our test content it's /conf/<config>/settings/cloudconfigs/commerce
         * The reason is that AEM has a specific CaConfig API implementation that reads the configuration
         * data from the jcr:content node of the configuration page, something which we cannot reproduce in
         * a unit test scenario.
         */
        Resource pageWithConfig = Mockito.spy(context.resourceResolver()
            .getResource("/content/pageG"));
        Mockito.when(pageWithConfig.adaptTo(GraphqlClient.class))
            .thenReturn(graphqlClient);
        MagentoGraphqlClient client = MagentoGraphqlClient.create(pageWithConfig);
        executeAndCheck(true, client, null);
    }

    @Test
    public void testMagentoStoreProperty() {
        // Get page which has the magentoStore property in its jcr:content node
        Resource resource = Mockito.spy(context.resourceResolver()
            .getResource("/content/pageA"));
        testMagentoStoreProperty(resource, true);
    }

    @Test
    public void testInheritedMagentoStoreProperty() {
        // Get page whose parent has the magentoStore property in its jcr:content node
        Resource resource = Mockito.spy(context.resourceResolver()
            .getResource("/content/pageB/pageC"));
        testMagentoStoreProperty(resource, true);
    }

    @Test
    public void testMissingMagentoStoreProperty() {
        // Get page whose parent has the magentoStore property in its jcr:content node
        Resource resource = Mockito.spy(context.resourceResolver()
            .getResource("/content/pageD"));
        testMagentoStoreProperty(resource, false);
    }

    @Test
    public void testOldMagentoStoreProperty() {
        // Get page which has the old cq:magentoStore property in its jcr:content node
        Resource resource = Mockito.spy(context.resourceResolver()
            .getResource("/content/pageE"));
        testMagentoStoreProperty(resource, true);
    }

    @Test
    public void testNewMagentoStoreProperty() {
        // Get page which has both the new magentoStore property and old cq:magentoStore property
        // in its jcr:content node and make sure the new one is prefered
        Resource resource = Mockito.spy(context.resourceResolver()
            .getResource("/content/pageF"));
        testMagentoStoreProperty(resource, true);
    }

    @Test
    public void testError() {
        // Get page which has the magentoStore property in its jcr:content node
        Resource resource = Mockito.spy(context.resourceResolver()
            .getResource("/content/pageA"));
        Mockito.when(resource.adaptTo(GraphqlClient.class))
            .thenReturn(null);

        MagentoGraphqlClient client = MagentoGraphqlClient.create(resource);
        Assert.assertNull(client);
    }

    @Test
    public void testWithLaunches() {

        Function<Resource, GraphqlClient> adapter = r -> {
            return r.getPath().equals("/content/pageA") ? graphqlClient : null;
        };
        context.registerAdapter(Resource.class, GraphqlClient.class, adapter);

        Page launchPage = context.currentPage("/content/launches/2020/04/23/my_launch/content/pageA");
        Resource launchResource = Mockito.spy(context.resourceResolver().getResource("/content/launches/2020/04/23/my_launch"));
        Resource productionResource = context.resourceResolver().getResource("/content/pageA");

        String liveDate = launchResource.getChild(JcrConstants.JCR_CONTENT).getValueMap().get("liveDate", String.class);
        OffsetDateTime offsetDateTime = DateTimeFormatter.ISO_OFFSET_DATE_TIME.parse(liveDate, OffsetDateTime::from);
        Long epoch = offsetDateTime.toEpochSecond();
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(epoch * 1000);

        Launch launch = Mockito.mock(Launch.class);
        context.registerAdapter(Resource.class, Launch.class, launch);
        Mockito.when(launch.getLiveDate()).thenReturn(cal);
        Mockito.when(launch.getResource()).thenReturn(launchResource);
        Mockito.when(launch.getSourceRootResource()).thenReturn(productionResource);
        Mockito.when(launchResource.adaptTo(Launch.class)).thenReturn(launch);

        MagentoGraphqlClient client = MagentoGraphqlClient.create(launchPage.getContentResource(), launchPage);
        executeAndCheck(false, client, String.valueOf(epoch));
    }

    /**
     * Matcher class used to check that the RequestOptions added by the wrapper are correct.
     */
    private static class RequestOptionsMatcher extends ArgumentMatcher<RequestOptions> {

        private List<Header> headers;

        private HttpMethod httpMethod;

        public RequestOptionsMatcher(List<Header> headers, HttpMethod httpMethod) {
            this.headers = headers;
            this.httpMethod = httpMethod;
        }

        @Override
        public boolean matches(Object obj) {
            if (!(obj instanceof RequestOptions)) {
                return false;
            }
            RequestOptions requestOptions = (RequestOptions) obj;
            try {
                // We expect a RequestOptions object with the custom Magento deserializer
                // and the same headers as the list given in the constructor

                if (requestOptions.getGson() != QueryDeserializer.getGson()) {
                    return false;
                }

                for (Header header : headers) {
                    if (!requestOptions.getHeaders()
                        .stream()
                        .anyMatch(h -> h.getName()
                            .equals(header.getName()) && h.getValue()
                                .equals(header.getValue()))) {
                        return false;
                    }
                }

                if (httpMethod != null && !httpMethod.equals(requestOptions.getHttpMethod())) {
                    return false;
                }

                return true;
            } catch (Exception e) {
                return false;
            }
        }
    }
}
