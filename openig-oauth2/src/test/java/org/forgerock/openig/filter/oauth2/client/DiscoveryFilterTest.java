/*
 * The contents of this file are subject to the terms of the Common Development and
 * Distribution License (the License). You may not use this file except in compliance with the
 * License.
 *
 * You can obtain a copy of the License at legal/CDDLv1.0.txt. See the License for the
 * specific language governing permission and limitations under the License.
 *
 * When distributing Covered Software, include this CDDL Header Notice in each file and include
 * the License file at legal/CDDLv1.0.txt. If applicable, add the following below the CDDL
 * Header, with the fields enclosed by brackets [] replaced by your own identifying
 * information: "Portions Copyright [year] [name of copyright owner]".
 *
 * Copyright 2015 ForgeRock AS.
 */
package org.forgerock.openig.filter.oauth2.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.forgerock.http.protocol.Response.newResponsePromise;
import static org.forgerock.http.util.Uris.urlDecode;
import static org.forgerock.json.JsonValue.array;
import static org.forgerock.json.JsonValue.field;
import static org.forgerock.json.JsonValue.json;
import static org.forgerock.json.JsonValue.object;
import static org.forgerock.openig.filter.oauth2.client.DiscoveryFilter.OPENID_SERVICE;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.initMocks;

import java.net.URI;
import java.net.URISyntaxException;

import org.forgerock.http.Handler;
import org.forgerock.http.protocol.Request;
import org.forgerock.http.protocol.Response;
import org.forgerock.http.protocol.Status;
import org.forgerock.openig.filter.oauth2.client.DiscoveryFilter.AccountIdentifier;
import org.forgerock.openig.heap.Heap;
import org.forgerock.openig.http.Exchange;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

/** Unit tests for the discovery filter class. */
@SuppressWarnings("javadoc")
public class DiscoveryFilterTest {
    private static final String REL_OPENID_ISSUER = "&rel=http%3A%2F%2Fopenid.net%2Fspecs%2Fconnect%2F1.0%2Fissuer";

    private Exchange exchange;

    @Captor
    private ArgumentCaptor<Request> captor;

    @Mock
    private Heap heap;

    @Mock
    private Handler handler;

    @BeforeMethod
    public void setUp() throws Exception {
        initMocks(this);
        exchange = new Exchange(null, new URI("www.example.com"));
    }

    @DataProvider
    private Object[][] givenInputAndNormalizedIdentifierExtracted() {
        return new Object[][] {
            { "acct:alice@example.com", "acct:alice@example.com" },
            { "acct:juliet%40capulet.example@shopping.example.com",
              "acct:juliet@capulet.example@shopping.example.com" },
            { "https://example.com/joe", "https://example.com/joe" },
            { "https://example.com:8080/joe", "https://example.com:8080/joe" },
            { "http://www.example.org/foo.html#bar", "http://www.example.org/foo.html" },
            { "http://www.example.org#bar", "http://www.example.org" },
            { "https://example.org:8080/joe#bar", "https://example.org:8080/joe" },
            { "alice@example.com", "acct:alice@example.com" },
            { "alice@example.com:8080", "acct:alice@example.com:8080" },
            { "joe@example.com@example.org", "acct:joe@example.com@example.org" },
            { "joe@example.org:8080/path", "acct:joe@example.org:8080/path" }};
    }

    @DataProvider
    private Object[][] inputAndExpectedUri() {
        return new Object[][] {
            // @Checkstyle:off
            {
                "acct:alice@example.com",
                "https://example.com/.well-known/webfinger?resource=acct%3Aalice%40example.com" + REL_OPENID_ISSUER },
            {
                "acct:joe@example.com",
                "https://example.com/.well-known/webfinger?resource=acct%3Ajoe%40example.com" + REL_OPENID_ISSUER },
            {
                "https://example.com/joe",
                "https://example.com/.well-known/webfinger?resource=https%3A%2F%2Fexample.com%2Fjoe" + REL_OPENID_ISSUER },
            {
                "https://example.com:8080/",
                "https://example.com:8080/.well-known/webfinger?resource=https%3A%2F%2Fexample.com%3A8080%2F"
                        + REL_OPENID_ISSUER },
            {
                "acct:juliet%40capulet.example@shopping.example.com",
                "https://shopping.example.com/.well-known/webfinger?"
                        + "resource=acct%3Ajuliet%40capulet.example%40shopping.example.com" + REL_OPENID_ISSUER },
            {
                "alice@example.com:8080",
                "https://example.com:8080/.well-known/webfinger?resource=acct%3Aalice%40example.com%3A8080"
                + REL_OPENID_ISSUER },
            {
                "https://www.example.com",
                "https://www.example.com/.well-known/webfinger?resource=https%3A%2F%2Fwww.example.com" + REL_OPENID_ISSUER },
            {
                "http://www.example.com",
                "http://www.example.com/.well-known/webfinger?resource=http%3A%2F%2Fwww.example.com" + REL_OPENID_ISSUER },
            {
                "joe@example.com@example.org",
                "https://example.org/.well-known/webfinger?resource=acct%3Ajoe%40example.com%40example.org"
                + REL_OPENID_ISSUER } };
            // @Checkstyle:on
    }

    @Test(expectedExceptions = IllegalArgumentException.class)
    public void shouldFailWhenInputIsNull() throws Exception {
        final DiscoveryFilter df = new DiscoveryFilter(handler, heap);
        df.extractFromInput(null);
    }

    @Test(expectedExceptions = IllegalArgumentException.class)
    public void shouldFailWhenInputIsEmpty() throws Exception {
        final DiscoveryFilter df = new DiscoveryFilter(handler, heap);
        df.extractFromInput("");
    }

    @Test(expectedExceptions = URISyntaxException.class)
    public void shouldFailWhenInputIsInvalid() throws Exception {
        final DiscoveryFilter df = new DiscoveryFilter(handler, heap);
        df.extractFromInput("+://zorg");
    }

    @Test(dataProvider = "givenInputAndNormalizedIdentifierExtracted")
    public void shouldExtractParameters(final String input, final String expected) throws Exception {
        final DiscoveryFilter df = new DiscoveryFilter(handler, heap);
        final AccountIdentifier account = df.extractFromInput(urlDecode(input));
        assertThat(account.getNormalizedIdentifier().toString()).isEqualTo(expected);
    }

    @Test(dataProvider = "inputAndExpectedUri")
    public void shouldReturnWebfingerUri(final String input, final String expected) throws Exception {
        final DiscoveryFilter df = new DiscoveryFilter(handler, heap);
        final AccountIdentifier account = df.extractFromInput(urlDecode(input));
        assertThat(df.buildWebFingerRequest(account).getUri().toString()).isEqualTo(expected);
    }

    @Test
    public void shouldPerformOpenIdIssuerDiscovery() throws Exception {
        // given
        final String givenWebFingerUri = "http://openam.example.com/.well-known/webfinger"
                                         + "?resource=http%3A%2F%2Fopenam.example.com%2Fjackson"
                                         + "&rel=http%3A%2F%2Fopenid.net%2Fspecs%2Fconnect%2F1.0%2Fissuer";

        final DiscoveryFilter df = new DiscoveryFilter(handler, heap);

        final AccountIdentifier account = df.extractFromInput(urlDecode("http://openam.example.com/jackson"));

        final Response response = new Response();
        response.setStatus(Status.OK);
        response.setEntity(json(object(field("links", array(
                                                        object(
                                                            field("rel" , "copyright"),
                                                            field("href", "http://www.example.com/copyright")),
                                                        object(
                                                           field("rel" , OPENID_SERVICE),
                                                           field("href", "http://localhost:8090/openam/oauth2")))))));
        when(handler.handle(eq(exchange), any(Request.class))).thenReturn(newResponsePromise(response));

        // when
        final URI openIdWellKnownUri = df.performOpenIdIssuerDiscovery(exchange, account);

        // then
        verify(handler).handle(eq(exchange), captor.capture());
        final Request request = captor.getValue();
        assertThat(request.getMethod()).isEqualTo("GET");
        assertThat(request.getUri().toString()).isEqualTo(givenWebFingerUri);
        assertThat(openIdWellKnownUri.toString()).endsWith("/.well-known/openid-configuration");
    }

    @Test(expectedExceptions = DiscoveryException.class)
    public void shouldFailPerformOpenIdIssuerDiscoveryWhenServerResponseDoNotContainOpenIdLink() throws Exception {
        // given
        final DiscoveryFilter df = new DiscoveryFilter(handler, heap);
        final AccountIdentifier account = df.extractFromInput(urlDecode("http://openam.example.com/jackson"));

        final Response response = new Response();
        response.setStatus(Status.TEAPOT);
        response.setEntity(json(object(field("links", array(
                                                        object(
                                                            field("rel" , "copyright"),
                                                            field("href", "http://www.example.com/copyright")))))));

        when(handler.handle(eq(exchange), any(Request.class))).thenReturn(newResponsePromise(response));

        // when
        df.performOpenIdIssuerDiscovery(exchange, account);
    }

    @Test(expectedExceptions = DiscoveryException.class)
    public void shouldFailPerformOpenIdIssuerDiscoveryWhenServerResponseContainInvalidJson() throws Exception {
        // given
        final DiscoveryFilter df = new DiscoveryFilter(handler, heap);
        final AccountIdentifier account = df.extractFromInput(urlDecode("http://openam.example.com/jackson"));

        final Response response = new Response();
        response.setStatus(Status.TEAPOT);
        response.setEntity(json(object(field("links", "not an array. Should fail"))));
        when(handler.handle(eq(exchange), any(Request.class))).thenReturn(newResponsePromise(response));

        // when
        df.performOpenIdIssuerDiscovery(exchange, account);
    }

    @Test(expectedExceptions = DiscoveryException.class)
    public void shouldFailWhenTheIssuerHrefIsNull() throws Exception {
        // given
        final DiscoveryFilter df = new DiscoveryFilter(handler, heap);
        final AccountIdentifier account = df.extractFromInput(urlDecode("http://openam.example.com/jackson"));

        final Response response = new Response();
        response.setStatus(Status.OK);
        response.setEntity(null);
        when(handler.handle(eq(exchange), any(Request.class))).thenReturn(newResponsePromise(response));

        // when
        df.performOpenIdIssuerDiscovery(exchange, account);
    }
}
