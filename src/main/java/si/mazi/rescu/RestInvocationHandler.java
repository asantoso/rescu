/**
 * Copyright (C) 2013 Matija Mazi
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal in
 * the Software without restriction, including without limitation the rights to
 * use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies
 * of the Software, and to permit persons to whom the Software is furnished to do
 * so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package si.mazi.rescu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.vertx.core.Handler;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClientResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import si.mazi.rescu.serialization.PlainTextResponseReader;
import si.mazi.rescu.serialization.ToStringRequestWriter;
import si.mazi.rescu.serialization.jackson.DefaultJacksonObjectMapperFactory;
import si.mazi.rescu.serialization.jackson.JacksonObjectMapperFactory;
import si.mazi.rescu.serialization.jackson.JacksonRequestWriter;
import si.mazi.rescu.serialization.jackson.JacksonResponseReader;

import javax.ws.rs.Path;
import javax.ws.rs.core.MediaType;
import java.io.IOException;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.Map;

/**
 * @author Matija Mazi
 */
public class RestInvocationHandler implements InvocationHandler {

    private static final Logger log = LoggerFactory.getLogger(RestInvocationHandler.class);

    private final ResponseReaderResolver responseReaderResolver;
    private final RequestWriterResolver requestWriterResolver;

    // private final HttpTemplate httpTemplate;
    private final String intfacePath;
    private final String baseUrl;
    private final ClientConfig config;

    private final Map<Method, RestMethodMetadata> methodMetadataCache = new HashMap<>();

    RestInvocationHandler(Class<?> restInterface, String url, ClientConfig config) {
        this.intfacePath = restInterface.getAnnotation(Path.class).value();
        this.baseUrl = url;

        if (config == null) {
            config = new ClientConfig(); //default config
        }

        this.config = config;

        //setup default readers/writers
        JacksonObjectMapperFactory mapperFactory = config.getJacksonObjectMapperFactory();
        if (mapperFactory == null) {
            mapperFactory = new DefaultJacksonObjectMapperFactory();
        }
        ObjectMapper mapper = mapperFactory.createObjectMapper();

        requestWriterResolver = new RequestWriterResolver();
        /*requestWriterResolver.addWriter(null,
                new NullRequestWriter());*/
        requestWriterResolver.addWriter(MediaType.APPLICATION_FORM_URLENCODED,
                new FormUrlEncodedRequestWriter());
        requestWriterResolver.addWriter(MediaType.APPLICATION_JSON,
                new JacksonRequestWriter(mapper));
        requestWriterResolver.addWriter(MediaType.TEXT_PLAIN,
                new ToStringRequestWriter());

        responseReaderResolver = new ResponseReaderResolver();
        responseReaderResolver.addReader(MediaType.APPLICATION_JSON,
                new JacksonResponseReader(mapper, this.config.isIgnoreHttpErrorCodes()));
        responseReaderResolver.addReader(MediaType.TEXT_PLAIN,
                new PlainTextResponseReader(this.config.isIgnoreHttpErrorCodes()));

                //setup http client
//        this.httpTemplate = new HttpTemplate(
//                this.config.getHttpConnTimeout(),
//                this.config.getHttpReadTimeout(),
//                this.config.getProxyHost(), this.config.getProxyPort(),
//                this.config.getSslSocketFactory(), this.config.getHostnameVerifier(), this.config.getOAuthConsumer());
    }

    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        HttpClient httpClient = this.config.getHttpClient();
        if (null == httpClient) {
            String msg = "[RestInvocationHandler] httpclient is null";
            try {
                String thName = Thread.currentThread().getName();
                throw new RuntimeException(thName+" -- " + msg);
            } catch (Exception e) {
                e.printStackTrace();
            }
            throw new RuntimeException(msg);
        }

        if (method.getDeclaringClass().equals(Object.class)) {
            return method.invoke(this, args);
        }

        RestMethodMetadata methodMetadata = getMetadata(method);
        methodMetadata.getParameterAnnotations();

        final Handler handler;
        if (args[args.length - 1] instanceof Handler) {
            handler = (Handler) args[args.length - 1];
        } else {
            handler = null;
        }

        // HttpURLConnection connection = null;

        RestInvocation invocation = null;
//        Object lock = getValueGenerator(args);
//        if (lock == null) {
//            lock = new Object(); // effectively no locking
//        }
        try {
            // synchronized (lock) {
                long fetchStartTs = 0;
                Handler<HttpClientResponse> httpRespHandler = httpResp -> {
                    if (httpResp.statusCode() != 200) {
                        System.err.println("fail");
                    }
                    httpResp.bodyHandler(buffer -> {
                        final long elapsedMillis = System.currentTimeMillis() - fetchStartTs;
                        System.out.println(String.format("# [RestInvocationHandler] elapsed:[%d] len:%s", elapsedMillis, buffer.length()));

                        String respBody = buffer.getString(0, buffer.length());
                        System.out.println("# [RestInvocationHandler] resp:" + respBody);

                        InvocationResult invocationResult = new InvocationResult(respBody, httpResp.statusCode());
                        try {
                            Object resp = mapInvocationResult(invocationResult, methodMetadata);
                            if (null != handler) {
                                handler.handle(resp);
                            }
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    });
                };
                invocation = createInvocation(method, args);
                invokeHttp(invocation, httpRespHandler);
            // }

            // final Object result = receiveAndMap(methodMetadata, connection);

            /** FOR TESTING ? */
            // makeAware(result, connection, invocation);
            return null;
        } catch (Exception e) {
            // final boolean madeAware = makeAware(e, connection, invocation);
//            if (config.isWrapUnexpectedExceptions() && !madeAware) {
//                throw new AwareException(e, invocation);
//            }
            throw e;
        }
    }

    private boolean makeAware(Object result, HttpURLConnection connection, RestInvocation invocation) {
        boolean madeAware = false;
        if (result instanceof InvocationAware) {
            try {
                ((InvocationAware) result).setInvocation(invocation);
                madeAware = true;
            } catch (Exception ex) {
                log.warn("Failed to set invocation on the InvocationAware", ex);
            }
        }
        if (result instanceof HttpResponseAware && connection != null) {
            try {
                ((HttpResponseAware) result).setResponseHeaders(connection.getHeaderFields());
                madeAware = true;
            } catch (Exception ex) {
                log.warn("Failed to set response headers on the HttpResponseAware", ex);
            }
        }
        return madeAware;
    }

    protected void invokeHttp(RestInvocation invocation, Handler<HttpClientResponse> handler) throws IOException {
        RestMethodMetadata methodMetadata = invocation.getMethodMetadata();

        RequestWriter requestWriter = requestWriterResolver.resolveWriter(invocation.getMethodMetadata());
        final String requestBody = requestWriter.writeBody(invocation);

        HttpMethod method = methodMetadata.getHttpMethod();
        io.vertx.core.http.HttpMethod vertxMethod = io.vertx.core.http.HttpMethod.valueOf(method.name());

        Map<String, String> headers = invocation.getHttpHeadersFromParams();

        HttpClient client = this.config.getHttpClient();
        HttpClientRequest request = client.requestAbs(vertxMethod, invocation.getInvocationUrl());
        request.handler(handler);

        for (String hk : headers.keySet()) {
            request.putHeader(hk, headers.get(hk));
        }
        int contentLength = (requestBody == null)? 0 : requestBody.length();
        request.putHeader("Content-Length", Integer.toString(contentLength));
        if (contentLength > 0) {
            request.end(Buffer.buffer(requestBody.getBytes(Charset.forName("UTF-8"))));
        }

        // return httpTemplate.send(invocation.getInvocationUrl(), requestBody, invocation.getAllHttpHeaders(), methodMetadata.getHttpMethod());
    }

//    protected Object receiveAndMap(RestMethodMetadata methodMetadata, HttpURLConnection connection) throws IOException {
//        InvocationResult invocationResult = httpTemplate.receive(connection);
//        return mapInvocationResult(invocationResult, methodMetadata);
//    }

    private static SynchronizedValueFactory getValueGenerator(Object[] args) {
        if (args != null) for (Object arg : args)
            if (arg instanceof SynchronizedValueFactory)
                return (SynchronizedValueFactory) arg;
        return null;
    }

    protected Object mapInvocationResult(InvocationResult invocationResult,
            RestMethodMetadata methodMetadata) throws IOException {
        return responseReaderResolver.resolveReader(methodMetadata).read(invocationResult, methodMetadata);
    }

    private RestMethodMetadata getMetadata(Method method) {
        RestMethodMetadata metadata = methodMetadataCache.get(method);
        if (metadata == null) {
            metadata = RestMethodMetadata.create(method, baseUrl, intfacePath);
            methodMetadataCache.put(method, metadata);
        }
        return metadata;
    }

    protected RestInvocation createInvocation(Method method, Object[] args) {
        return RestInvocation.create(
                requestWriterResolver, getMetadata(method), args, config.getDefaultParamsMap()
        );
    }
}
