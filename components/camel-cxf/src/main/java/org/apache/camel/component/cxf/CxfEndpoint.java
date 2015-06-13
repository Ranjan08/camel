/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.camel.component.cxf;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.xml.namespace.QName;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import javax.xml.transform.Source;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.sax.SAXSource;
import javax.xml.transform.stax.StAXSource;
import javax.xml.transform.stream.StreamSource;
import javax.xml.ws.Provider;
import javax.xml.ws.WebServiceProvider;
import javax.xml.ws.handler.Handler;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import org.apache.camel.CamelContext;
import org.apache.camel.CamelException;
import org.apache.camel.Consumer;
import org.apache.camel.Processor;
import org.apache.camel.Producer;
import org.apache.camel.RuntimeCamelException;
import org.apache.camel.Service;
import org.apache.camel.component.cxf.common.header.CxfHeaderFilterStrategy;
import org.apache.camel.component.cxf.common.message.CxfConstants;
import org.apache.camel.component.cxf.feature.CXFMessageDataFormatFeature;
import org.apache.camel.component.cxf.feature.PayLoadDataFormatFeature;
import org.apache.camel.component.cxf.feature.RAWDataFormatFeature;
import org.apache.camel.impl.DefaultEndpoint;
import org.apache.camel.impl.SynchronousDelegateProducer;
import org.apache.camel.spi.HeaderFilterStrategy;
import org.apache.camel.spi.HeaderFilterStrategyAware;
import org.apache.camel.spi.UriEndpoint;
import org.apache.camel.spi.UriParam;
import org.apache.camel.spi.UriPath;
import org.apache.camel.util.EndpointHelper;
import org.apache.camel.util.ObjectHelper;
import org.apache.camel.util.UnsafeUriCharactersEncoder;
import org.apache.cxf.Bus;
import org.apache.cxf.BusFactory;
import org.apache.cxf.binding.BindingConfiguration;
import org.apache.cxf.common.classloader.ClassLoaderUtils;
import org.apache.cxf.common.injection.ResourceInjector;
import org.apache.cxf.common.util.ClassHelper;
import org.apache.cxf.common.util.ModCountCopyOnWriteArrayList;
import org.apache.cxf.common.util.ReflectionUtil;
import org.apache.cxf.configuration.security.AuthorizationPolicy;
import org.apache.cxf.databinding.DataBinding;
import org.apache.cxf.databinding.source.SourceDataBinding;
import org.apache.cxf.endpoint.Client;
import org.apache.cxf.endpoint.ClientImpl;
import org.apache.cxf.endpoint.Endpoint;
import org.apache.cxf.feature.Feature;
import org.apache.cxf.feature.LoggingFeature;
import org.apache.cxf.frontend.ClientFactoryBean;
import org.apache.cxf.frontend.ServerFactoryBean;
import org.apache.cxf.headers.Header;
import org.apache.cxf.interceptor.Interceptor;
import org.apache.cxf.jaxws.JaxWsClientFactoryBean;
import org.apache.cxf.jaxws.JaxWsServerFactoryBean;
import org.apache.cxf.jaxws.context.WebServiceContextResourceResolver;
import org.apache.cxf.jaxws.handler.AnnotationHandlerChainBuilder;
import org.apache.cxf.jaxws.support.JaxWsEndpointImpl;
import org.apache.cxf.jaxws.support.JaxWsServiceFactoryBean;
import org.apache.cxf.logging.FaultListener;
import org.apache.cxf.message.Attachment;
import org.apache.cxf.message.Message;
import org.apache.cxf.message.MessageContentsList;
import org.apache.cxf.resource.DefaultResourceManager;
import org.apache.cxf.resource.ResourceManager;
import org.apache.cxf.resource.ResourceResolver;
import org.apache.cxf.service.model.BindingOperationInfo;
import org.apache.cxf.service.model.MessagePartInfo;
import org.apache.cxf.staxutils.StaxSource;
import org.apache.cxf.staxutils.StaxUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Defines the <a href="http://camel.apache.org/cxf.html">CXF Endpoint</a>.
 * It contains a list of properties for CXF endpoint including {@link DataFormat},
 * {@link CxfBinding}, and {@link HeaderFilterStrategy}.  The default DataFormat
 * mode is {@link DataFormat#POJO}.
 */
@UriEndpoint(scheme = "cxf", title = "CXF", syntax = "cxf:beanId:address", consumerClass = CxfConsumer.class, label = "soap,webservice")
public class CxfEndpoint extends DefaultEndpoint implements HeaderFilterStrategyAware, Service, Cloneable {

    private static final Logger LOG = LoggerFactory.getLogger(CxfEndpoint.class);

    protected Bus bus;

    @UriPath(description = "To lookup an existing configured CxfEndpoint. Must used bean: as prefix.")
    private String beanId;
    @UriPath
    private String address;

    @UriParam
    private boolean createBus;
    @UriParam
    private String wsdlURL;
    private Class<?> serviceClass;
    private QName portName;
    private QName serviceName;
    @UriParam
    private String portNameString;
    @UriParam
    private String serviceNameString;
    @UriParam
    private String defaultOperationName;
    @UriParam
    private String defaultOperationNamespace;
    // This is for invoking the CXFClient with wrapped parameters of unwrapped parameters
    @UriParam
    private boolean isWrapped;
    // This is for marshal or unmarshal message with the document-literal wrapped or unwrapped style
    @UriParam
    private Boolean wrappedStyle;
    @UriParam
    private Boolean allowStreaming;
    @UriParam(defaultValue = "POJO")
    private DataFormat dataFormat = DataFormat.POJO;
    @UriParam
    private String publishedEndpointUrl;
    @UriParam(defaultValue = "true")
    private boolean inOut = true;
    private CxfBinding cxfBinding;
    private HeaderFilterStrategy headerFilterStrategy;
    private AtomicBoolean getBusHasBeenCalled = new AtomicBoolean(false);
    @UriParam
    private boolean isSetDefaultBus;
    @UriParam
    private boolean loggingFeatureEnabled;
    @UriParam
    private int loggingSizeLimit;
    @UriParam
    private boolean mtomEnabled;
    @UriParam
    private boolean skipPayloadMessagePartCheck;
    @UriParam
    private boolean skipFaultLogging;
    @UriParam
    private boolean mergeProtocolHeaders;
    private Map<String, Object> properties;
    private List<Interceptor<? extends Message>> in = new ModCountCopyOnWriteArrayList<Interceptor<? extends Message>>();
    private List<Interceptor<? extends Message>> out = new ModCountCopyOnWriteArrayList<Interceptor<? extends Message>>();
    private List<Interceptor<? extends Message>> outFault = new ModCountCopyOnWriteArrayList<Interceptor<? extends Message>>();
    private List<Interceptor<? extends Message>> inFault = new ModCountCopyOnWriteArrayList<Interceptor<? extends Message>>();
    private List<Feature> features = new ModCountCopyOnWriteArrayList<Feature>();

    @SuppressWarnings("rawtypes")
    private List<Handler> handlers;
    private List<String> schemaLocations;
    @UriParam
    private String transportId;
    @UriParam
    private String bindingId;
    
    private BindingConfiguration bindingConfig;
    private DataBinding dataBinding;
    private Object serviceFactoryBean;
    private CxfEndpointConfigurer configurer;
    
    // The continuation timeout value for CXF continuation to use
    @UriParam(defaultValue = "30000")
    private long continuationTimeout = 30000;
    
    // basic authentication option for the CXF client
    @UriParam
    private String username;
    @UriParam
    private String password;

    public CxfEndpoint() {
    }

    public CxfEndpoint(String remaining, CxfComponent cxfComponent) {
        super(remaining, cxfComponent);
        setAddress(remaining);
    }

    @Deprecated
    public CxfEndpoint(String remaining, CamelContext context) {
        super(remaining, context);
        setAddress(remaining);
    }

    @Deprecated
    public CxfEndpoint(String remaining) {
        super(remaining);
        setAddress(remaining);
    }

    public CxfEndpoint copy() {
        try {
            return (CxfEndpoint)this.clone();
        } catch (CloneNotSupportedException e) {
            throw new RuntimeCamelException(e);
        }
    }

    // This method is for CxfComponent setting the EndpointUri
    protected void updateEndpointUri(String endpointUri) {
        super.setEndpointUri(UnsafeUriCharactersEncoder.encodeHttpURI(endpointUri));
    }

    public Producer createProducer() throws Exception {
        Producer answer = new CxfProducer(this);
        if (isSynchronous()) {
            return new SynchronousDelegateProducer(answer);
        } else {
            return answer;
        }
    }

    public Consumer createConsumer(Processor processor) throws Exception {
        CxfConsumer answer = new CxfConsumer(this, processor);
        configureConsumer(answer);
        return answer;
    }

    public boolean isSingleton() {
        return true;
    }

    /**
     * Populate server factory bean
     */
    protected void setupServerFactoryBean(ServerFactoryBean sfb, Class<?> cls) {

        // address
        sfb.setAddress(getAddress());

        sfb.setServiceClass(cls);

        sfb.setInInterceptors(in);
        sfb.setOutInterceptors(out);
        sfb.setOutFaultInterceptors(outFault);
        sfb.setInFaultInterceptors(inFault); 
        sfb.setFeatures(features);
        if (schemaLocations != null) {
            sfb.setSchemaLocations(schemaLocations);
        }
        if (bindingConfig != null) {
            sfb.setBindingConfig(bindingConfig);
        }
        
        if (dataBinding != null) {
            sfb.setDataBinding(dataBinding);
        }
        
        if (serviceFactoryBean != null) {
            setServiceFactory(sfb, serviceFactoryBean);
        }
        
        if (sfb instanceof JaxWsServerFactoryBean && handlers != null) {
            ((JaxWsServerFactoryBean)sfb).setHandlers(handlers);
        }
        if (getTransportId() != null) {
            sfb.setTransportId(getTransportId());
        }
        if (getBindingId() != null) {
            sfb.setBindingId(getBindingId());
        }
        
        // wsdl url
        if (getWsdlURL() != null) {
            sfb.setWsdlURL(getWsdlURL());
        }

        // service  name qname
        if (getServiceName() != null) {
            sfb.setServiceName(getServiceName());
        }

        // port qname
        if (getPortName() != null) {
            sfb.setEndpointName(getPortName());
        }

        // apply feature here
        if (!CxfEndpointUtils.hasAnnotation(cls, WebServiceProvider.class)) {
            if (getDataFormat() == DataFormat.PAYLOAD) {
                sfb.getFeatures().add(new PayLoadDataFormatFeature(allowStreaming));
            } else if (getDataFormat().dealias() == DataFormat.CXF_MESSAGE) {
                sfb.getFeatures().add(new CXFMessageDataFormatFeature());
                sfb.setDataBinding(new SourceDataBinding());
            } else if (getDataFormat().dealias() == DataFormat.RAW) {
                RAWDataFormatFeature feature = new RAWDataFormatFeature();
                feature.addInIntercepters(getInInterceptors());
                feature.addOutInterceptors(getOutInterceptors());
                sfb.getFeatures().add(feature);
            }
        } else {
            LOG.debug("Ignore DataFormat mode {} since SEI class is annotated with WebServiceProvider", getDataFormat());
        }

        if (isLoggingFeatureEnabled()) {
            if (getLoggingSizeLimit() > 0) {
                sfb.getFeatures().add(new LoggingFeature(getLoggingSizeLimit()));
            } else {
                sfb.getFeatures().add(new LoggingFeature());
            }
        }

        if (getDataFormat() == DataFormat.PAYLOAD) {
            sfb.setDataBinding(new HybridSourceDataBinding());
        }

        // set the document-literal wrapped style
        if (getWrappedStyle() != null && getDataFormat().dealias() != DataFormat.CXF_MESSAGE) {
            setWrapped(sfb, getWrappedStyle());
        }

        // any optional properties
        if (getProperties() != null) {
            if (sfb.getProperties() != null) {
                // add to existing properties
                sfb.getProperties().putAll(getProperties());
            } else {
                sfb.setProperties(getProperties());
            }
            LOG.debug("ServerFactoryBean: {} added properties: {}", sfb, getProperties());
        }
        if (this.isSkipPayloadMessagePartCheck()) {
            if (sfb.getProperties() == null) {
                sfb.setProperties(new HashMap<String, Object>());                
            }
            sfb.getProperties().put("soap.no.validate.parts", Boolean.TRUE);
        }
        
        if (this.isSkipFaultLogging()) {
            if (sfb.getProperties() == null) {
                sfb.setProperties(new HashMap<String, Object>());                
            }
            sfb.getProperties().put(FaultListener.class.getName(), new NullFaultListener());
        }

        sfb.setBus(getBus());
        sfb.setStart(false);
        if (getCxfEndpointConfigurer() != null) {
            getCxfEndpointConfigurer().configure(sfb);
        }
    }

    /**
     * Create a client factory bean object.  Notice that the serviceClass <b>must</b> be
     * an interface.
     */
    protected ClientFactoryBean createClientFactoryBean(Class<?> cls) throws CamelException {
        if (CxfEndpointUtils.hasWebServiceAnnotation(cls)) {
            return new JaxWsClientFactoryBean() {
                @Override
                protected Client createClient(Endpoint ep) {
                    return new CamelCxfClientImpl(getBus(), ep);
                }
            };
        } else {
            return new ClientFactoryBean() {
                @Override
                protected Client createClient(Endpoint ep) {
                    return new CamelCxfClientImpl(getBus(), ep);
                }
            };
        }
    }

    /**
     * Create a client factory bean object without serviceClass interface.
     */
    protected ClientFactoryBean createClientFactoryBean() {
        ClientFactoryBean cf = new ClientFactoryBean() {
            @Override
            protected Client createClient(Endpoint ep) {
                return new CamelCxfClientImpl(getBus(), ep);
            }

            @Override
            protected void initializeAnnotationInterceptors(Endpoint ep, Class<?> cls) {
                // Do nothing here
            }
        };
        for (Method m : cf.getClass().getMethods()) {
            if ("setServiceFactory".equals(m.getName())) {
                try {
                    // Set Object class as the service class of WSDLServiceFactoryBean 
                    ReflectionUtil.setAccessible(m).invoke(cf, new WSDLServiceFactoryBean(Object.class));
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        }
        return cf;
    }

    protected void setupHandlers(ClientFactoryBean factoryBean, Client client) 
        throws Exception {

        if (factoryBean instanceof JaxWsClientFactoryBean && handlers != null) {
            AnnotationHandlerChainBuilder
            builder = new AnnotationHandlerChainBuilder();
            Method m = factoryBean.getClass().getMethod("getServiceFactory");
            JaxWsServiceFactoryBean sf = (JaxWsServiceFactoryBean)m.invoke(factoryBean);
            @SuppressWarnings("rawtypes")
            List<Handler> chain = new ArrayList<Handler>(handlers);

            chain.addAll(builder.buildHandlerChainFromClass(sf.getServiceClass(),
                                                            sf.getEndpointInfo().getName(),
                                                            sf.getServiceQName(),
                                                            factoryBean.getBindingId()));

            if (!chain.isEmpty()) {
                ResourceManager resourceManager = getBus().getExtension(ResourceManager.class);
                List<ResourceResolver> resolvers = resourceManager.getResourceResolvers();
                resourceManager = new DefaultResourceManager(resolvers);
                resourceManager.addResourceResolver(new WebServiceContextResourceResolver());
                ResourceInjector injector = new ResourceInjector(resourceManager);
                for (Handler<?> h : chain) {
                    if (Proxy.isProxyClass(h.getClass()) && getServiceClass() != null) {
                        injector.inject(h, getServiceClass());
                        injector.construct(h, getServiceClass());
                    } else {
                        injector.inject(h);
                        injector.construct(h);
                    }
                }
            }

            ((JaxWsEndpointImpl)client.getEndpoint()).getJaxwsBinding().setHandlerChain(chain);
        }
    }

    protected void setupClientFactoryBean(ClientFactoryBean factoryBean, Class<?> cls) {
        if (cls != null) {
            factoryBean.setServiceClass(cls);
        }
        factoryBean.setInInterceptors(in);
        factoryBean.setOutInterceptors(out);
        factoryBean.setOutFaultInterceptors(outFault);
        factoryBean.setInFaultInterceptors(inFault);
        factoryBean.setFeatures(features);
        factoryBean.setTransportId(transportId);
        factoryBean.setBindingId(bindingId);
        
        if (bindingConfig != null) {
            factoryBean.setBindingConfig(bindingConfig);
        }
        
        if (dataBinding != null) {
            factoryBean.setDataBinding(dataBinding);
        }
        
        if (serviceFactoryBean != null) {
            setServiceFactory(factoryBean, serviceFactoryBean);
        }

        // address
        factoryBean.setAddress(getAddress());

        // wsdl url
        if (getWsdlURL() != null) {
            factoryBean.setWsdlURL(getWsdlURL());
        }

        // service name qname
        if (getServiceName() != null) {
            factoryBean.setServiceName(getServiceName());
        }

        // port name qname
        if (getPortName() != null) {
            factoryBean.setEndpointName(getPortName());
        }

        // apply feature here
        if (getDataFormat().dealias() == DataFormat.RAW) {
            RAWDataFormatFeature feature = new RAWDataFormatFeature();
            feature.addInIntercepters(getInInterceptors());
            feature.addOutInterceptors(getOutInterceptors());
            factoryBean.getFeatures().add(feature);
        } else if (getDataFormat().dealias() == DataFormat.CXF_MESSAGE) {
            factoryBean.getFeatures().add(new CXFMessageDataFormatFeature());
            factoryBean.setDataBinding(new SourceDataBinding());
        } else if (getDataFormat() == DataFormat.PAYLOAD) {
            factoryBean.getFeatures().add(new PayLoadDataFormatFeature(allowStreaming));
            factoryBean.setDataBinding(new HybridSourceDataBinding());
        }

        if (isLoggingFeatureEnabled()) {
            if (getLoggingSizeLimit() > 0) {
                factoryBean.getFeatures().add(new LoggingFeature(getLoggingSizeLimit()));
            } else {
                factoryBean.getFeatures().add(new LoggingFeature());
            }
        }

        // set the document-literal wrapped style
        if (getWrappedStyle() != null) {
            setWrapped(factoryBean, getWrappedStyle());
        }
        
        // any optional properties
        if (getProperties() != null) {
            if (factoryBean.getProperties() != null) {
                // add to existing properties
                factoryBean.getProperties().putAll(getProperties());
            } else {
                factoryBean.setProperties(getProperties());
            }
            LOG.debug("ClientFactoryBean: {} added properties: {}", factoryBean, getProperties());
        }
        
        // setup the basic authentication property
        if (ObjectHelper.isNotEmpty(username)) {
            AuthorizationPolicy authPolicy = new AuthorizationPolicy();
            authPolicy.setUserName(username);
            authPolicy.setPassword(password);
            factoryBean.getProperties().put(AuthorizationPolicy.class.getName(), authPolicy);
        }
        
        if (this.isSkipPayloadMessagePartCheck()) {
            if (factoryBean.getProperties() == null) {
                factoryBean.setProperties(new HashMap<String, Object>());                
            }
            factoryBean.getProperties().put("soap.no.validate.parts", Boolean.TRUE);
        }
        
        if (this.isSkipFaultLogging()) {
            if (factoryBean.getProperties() == null) {
                factoryBean.setProperties(new HashMap<String, Object>());                
            }
            factoryBean.getProperties().put(FaultListener.class.getName(), new NullFaultListener());
        }

        factoryBean.setBus(getBus());
        if (getCxfEndpointConfigurer() != null) {
            getCxfEndpointConfigurer().configure(factoryBean);
        }
    }

    // Package private methods
    // -------------------------------------------------------------------------

    private void setWrapped(Object factoryBean, boolean wrapped) {
        try {
            Object sf = factoryBean.getClass().getMethod("getServiceFactory").invoke(factoryBean);
            sf.getClass().getMethod("setWrapped", Boolean.TYPE).invoke(sf, wrapped);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    private void setServiceFactory(Object factoryBean, Object serviceFactoryBean2) {
        for (Method m : factoryBean.getClass().getMethods()) {
            if ("setServiceFactory".equals(m.getName())
                && m.getParameterTypes()[0].isInstance(serviceFactoryBean2)) {
                try {
                    ReflectionUtil.setAccessible(m).invoke(factoryBean, serviceFactoryBean2);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        }
    }

    /**
     * Create a CXF client object
     */
    Client createClient() throws Exception {

        // get service class
        if (getDataFormat().equals(DataFormat.POJO)) {
            ObjectHelper.notNull(getServiceClass(), CxfConstants.SERVICE_CLASS);
        }

        if (getWsdlURL() == null && getServiceClass() == null) {
            // no WSDL and serviceClass specified, set our default serviceClass
            setServiceClass(org.apache.camel.component.cxf.DefaultSEI.class.getName());
            setDefaultOperationNamespace(CxfConstants.DISPATCH_NAMESPACE);
            setDefaultOperationName(CxfConstants.DISPATCH_DEFAULT_OPERATION_NAMESPACE);
            if (getDataFormat().equals(DataFormat.PAYLOAD)) {
                setSkipPayloadMessagePartCheck(true);
            }
        }

        Class<?> cls = null;
        if (getServiceClass() != null) {
            cls = getServiceClass();
            // create client factory bean
            ClientFactoryBean factoryBean = createClientFactoryBean(cls);
            // setup client factory bean
            setupClientFactoryBean(factoryBean, cls);
            Client client = factoryBean.create();
            // setup the handlers
            setupHandlers(factoryBean, client);
            return client;
        } else {
            // create the client without service class

            checkName(portName, "endpoint/port name");
            checkName(serviceName, "service name");

            ClientFactoryBean factoryBean = createClientFactoryBean();
            // setup client factory bean
            setupClientFactoryBean(factoryBean, null);
            return factoryBean.create();
        }
    }

    void checkName(Object value, String name) {
        if (ObjectHelper.isEmpty(value)) {
            LOG.warn("The " + name + " of " + this.getEndpointUri() + " is empty, cxf will try to load the first one in wsdl for you.");
        }
    }

    /**
     * Create a CXF server factory bean
     */
    ServerFactoryBean createServerFactoryBean() throws Exception {

        Class<?> cls = null;
        if (getDataFormat() == DataFormat.POJO) {
            ObjectHelper.notNull(getServiceClass(), CxfConstants.SERVICE_CLASS);
        }
        
        if (getWsdlURL() == null && getServiceClass() == null) {
            // no WSDL and serviceClass specified, set our default serviceClass
            if (getDataFormat().equals(DataFormat.PAYLOAD)) {
                setServiceClass(org.apache.camel.component.cxf.DefaultPayloadProviderSEI.class.getName());
            }
        }
        
        if (getServiceClass() != null) {
            cls = getServiceClass();
        }

        // create server factory bean
        // Shouldn't use CxfEndpointUtils.getServerFactoryBean(cls) as it is for
        // CxfSoapComponent
        ServerFactoryBean answer = null;

        if (cls == null) {
            checkName(portName, " endpoint/port name");
            checkName(serviceName, " service name");
            answer = new JaxWsServerFactoryBean(new WSDLServiceFactoryBean()) {
                {
                    doInit = false;
                }
            };
            cls = Provider.class;
        } else if (CxfEndpointUtils.hasWebServiceAnnotation(cls)) {
            answer = new JaxWsServerFactoryBean();
        } else {
            answer = new ServerFactoryBean();
        }

        // setup server factory bean
        setupServerFactoryBean(answer, cls);
        return answer;
    }

    protected String resolvePropertyPlaceholders(String str) {
        try {
            if (getCamelContext() != null) {
                return getCamelContext().resolvePropertyPlaceholders(str);
            } else {
                return str;
            }
        } catch (Exception ex) {
            throw ObjectHelper.wrapRuntimeCamelException(ex);
        }
    }

    // Properties
    // -------------------------------------------------------------------------


    public String getBeanId() {
        return beanId;
    }

    public void setBeanId(String beanId) {
        this.beanId = beanId;
    }

    public DataFormat getDataFormat() {
        return dataFormat;
    }

    public void setDataFormat(DataFormat format) {
        dataFormat = format;
    }

    public String getPublishedEndpointUrl() {
        return resolvePropertyPlaceholders(publishedEndpointUrl);
    }

    public void setPublishedEndpointUrl(String url) {
        publishedEndpointUrl = url;
    }

    public String getWsdlURL() {
        return resolvePropertyPlaceholders(wsdlURL);
    }

    public void setWsdlURL(String url) {
        wsdlURL = url;
    }

    public Class<?> getServiceClass() {
        return serviceClass;
    }

    public void setServiceClass(Class<?> cls) {
        serviceClass = cls;
    }

    public void setServiceClass(Object instance) {
        serviceClass = ClassHelper.getRealClass(instance);
    }
    
    public void setServiceClass(String type) throws ClassNotFoundException {
        if (ObjectHelper.isEmpty(type)) {
            throw new IllegalArgumentException("The serviceClass option can neither be null nor an empty String.");
        }
        serviceClass = ClassLoaderUtils.loadClass(resolvePropertyPlaceholders(type), getClass());
    }

    public void setServiceNameString(String service) {
        serviceNameString = service;
    }

    public void setServiceName(QName service) {
        serviceName = service;
    }

    public QName getServiceName() {
        if (serviceName == null && serviceNameString != null) {
            serviceName = QName.valueOf(resolvePropertyPlaceholders(serviceNameString));
        }
        return serviceName;
    }

    public QName getPortName() {
        if (portName == null && portNameString != null) {
            portName = QName.valueOf(resolvePropertyPlaceholders(portNameString));
        }
        return portName;
    }

    public void setPortName(QName port) {
        portName = port;
    }

    public void setEndpointNameString(String port) {
        portNameString = port;
    }

    public void setEndpointName(QName port) {
        portName = port;
    }

    public String getDefaultOperationName() {
        return resolvePropertyPlaceholders(defaultOperationName);
    }

    public void setDefaultOperationName(String name) {
        defaultOperationName = name;
    }

    public String getDefaultOperationNamespace() {
        return resolvePropertyPlaceholders(defaultOperationNamespace);
    }

    public void setDefaultOperationNamespace(String namespace) {
        defaultOperationNamespace = namespace;
    }

    public boolean isInOut() {
        return inOut;
    }

    public void setInOut(boolean inOut) {
        this.inOut = inOut;
    }

    public boolean isWrapped() {
        return isWrapped;
    }

    public void setWrapped(boolean wrapped) {
        isWrapped = wrapped;
    }

    public Boolean getWrappedStyle() {
        return wrappedStyle;
    }

    public void setWrappedStyle(Boolean wrapped) {
        wrappedStyle = wrapped;
    }
    
    public void setAllowStreaming(Boolean b) {
        allowStreaming = b;
    }

    public Boolean getAllowStreaming() {
        return allowStreaming;
    }

    public void setCxfBinding(CxfBinding cxfBinding) {
        this.cxfBinding = cxfBinding;
    }

    public CxfBinding getCxfBinding() {
        return cxfBinding;
    }

    public void setHeaderFilterStrategy(HeaderFilterStrategy headerFilterStrategy) {
        this.headerFilterStrategy = headerFilterStrategy;
        if (cxfBinding instanceof HeaderFilterStrategyAware) {
            ((HeaderFilterStrategyAware) cxfBinding).setHeaderFilterStrategy(headerFilterStrategy);
        }
    }

    public HeaderFilterStrategy getHeaderFilterStrategy() {
        return headerFilterStrategy;
    }

    public void setBus(Bus bus) {
        this.bus = bus;
        this.createBus = false;
    }

    public Bus getBus() {
        if (bus == null) {
            bus = CxfEndpointUtils.createBus(getCamelContext());
            this.createBus = true;
            LOG.debug("Using DefaultBus {}", bus);
        }

        if (!getBusHasBeenCalled.getAndSet(true) && isSetDefaultBus) {
            BusFactory.setDefaultBus(bus);
            LOG.debug("Set bus {} as thread default bus", bus);
        }
        return bus;
    }

    public void setSetDefaultBus(boolean isSetDefaultBus) {
        this.isSetDefaultBus = isSetDefaultBus;
    }

    public boolean isSetDefaultBus() {
        return isSetDefaultBus;
    }

    public void setLoggingFeatureEnabled(boolean loggingFeatureEnabled) {
        this.loggingFeatureEnabled = loggingFeatureEnabled;
    }

    public boolean isLoggingFeatureEnabled() {
        return loggingFeatureEnabled;
    }

    public int getLoggingSizeLimit() {
        return loggingSizeLimit;
    }

    public void setLoggingSizeLimit(int loggingSizeLimit) {
        this.loggingSizeLimit = loggingSizeLimit;
    }

    protected boolean isSkipPayloadMessagePartCheck() {
        return skipPayloadMessagePartCheck;
    }

    protected void setSkipPayloadMessagePartCheck(boolean skipPayloadMessagePartCheck) {
        this.skipPayloadMessagePartCheck = skipPayloadMessagePartCheck;
    }

    public Map<String, Object> getProperties() {
        return properties;
    }
    
    public void setCamelContext(CamelContext c) {
        super.setCamelContext(c);
        if (this.properties != null) {
            try {
                EndpointHelper.setReferenceProperties(getCamelContext(),
                                             this,
                                             this.properties);
                EndpointHelper.setProperties(getCamelContext(),
                                             this,
                                             this.properties);
            } catch (Throwable e) {
                // TODO: Why dont't we rethrown this exception
                LOG.warn("Error setting CamelContext. This exception will be ignored.", e);
            }
        }
    }

    public void setProperties(Map<String, Object> properties) {
        if (this.properties == null) {
            this.properties = properties;
        } else {
            this.properties.putAll(properties);
        }
        if (getCamelContext() != null && this.properties != null) {
            try {
                EndpointHelper.setReferenceProperties(getCamelContext(),
                                             this,
                                             this.properties);
                EndpointHelper.setProperties(getCamelContext(),
                                             this,
                                             this.properties);
            } catch (Throwable e) {
                // TODO: Why dont't we rethrown this exception
                LOG.warn("Error setting properties. This exception will be ignored.", e);
            }
        }
    }

    @Override
    protected void doStart() throws Exception {
        if (headerFilterStrategy == null) {
            headerFilterStrategy = new CxfHeaderFilterStrategy();
        }
        if (cxfBinding == null) {
            cxfBinding = new DefaultCxfBinding();
        }
        if (cxfBinding instanceof HeaderFilterStrategyAware) {
            ((HeaderFilterStrategyAware) cxfBinding).setHeaderFilterStrategy(getHeaderFilterStrategy());
        }
    }

    @Override
    protected void doStop() throws Exception {
        // we should consider to shutdown the bus if the bus is created by cxfEndpoint
        if (createBus && bus != null) {
            LOG.info("shutdown the bus ... " + bus);
            getBus().shutdown(false);
            // clean up the bus to create a new one if the endpoint is started again
            bus = null;
        }
    }

    public void setAddress(String address) {
        super.setEndpointUri(UnsafeUriCharactersEncoder.encodeHttpURI(address));
        this.address = address;
    }

    public String getAddress() {
        return resolvePropertyPlaceholders(address);
    }

    public void setMtomEnabled(boolean mtomEnabled) {
        this.mtomEnabled = mtomEnabled;
    }

    public boolean isMtomEnabled() {
        return mtomEnabled;
    }
    
    public String getPassword() {
        return password;
    }
    
    public void setPassword(String password) {
        this.password = password;
    }
    
    public String getUsername() {
        return username;
    }
    
    public void setUsername(String username) {
        this.username = username;
    }

    /**
     * We need to override the {@link ClientImpl#setParameters} method
     * to insert parameters into CXF Message for {@link DataFormat#PAYLOAD} mode.
     */
    class CamelCxfClientImpl extends ClientImpl {

        public CamelCxfClientImpl(Bus bus, Endpoint ep) {
            super(bus, ep);
        }

        public Bus getBus() {
            return bus;
        }

        @SuppressWarnings("unchecked")
        @Override
        protected void setParameters(Object[] params, Message message) {

            Object attachments = message.get(CxfConstants.CAMEL_CXF_ATTACHMENTS);
            if (attachments != null) {
                message.setAttachments((Collection<Attachment>) attachments);
                message.remove(CxfConstants.CAMEL_CXF_ATTACHMENTS);
            }

            // Don't try to reset the parameters if the parameter is not CxfPayload instance
            // as the setParameter will be called more than once when using the fail over feature
            if (DataFormat.PAYLOAD == message.get(DataFormat.class) && params[0] instanceof CxfPayload) {

                CxfPayload<?> payload = (CxfPayload<?>) params[0];
                List<Source> elements = payload.getBodySources();

                BindingOperationInfo boi = message.get(BindingOperationInfo.class);
                MessageContentsList content = new MessageContentsList();
                int i = 0;

                for (MessagePartInfo partInfo : boi.getInput().getMessageParts()) {
                    if (elements.size() > i) {
                        if (isSkipPayloadMessagePartCheck()) {
                            content.put(partInfo, elements.get(i++));
                        } else {
                            String name = findName(elements, i);
                            if (partInfo.getConcreteName().getLocalPart().equals(name)) {
                                content.put(partInfo, elements.get(i++));
                            }
                        }
                    }
                }

                if (elements != null && content.size() < elements.size()) {
                    throw new IllegalArgumentException("The PayLoad elements cannot fit with the message parts of the BindingOperation. Please check the BindingOperation and PayLoadMessage.");
                }

                message.setContent(List.class, content);
                message.put(Header.HEADER_LIST, payload.getHeaders());
            } else {
                super.setParameters(params, message);
            }

            message.remove(DataFormat.class);
        }

        private String findName(List<Source> sources, int i) {
            Source source = sources.get(i);
            XMLStreamReader r = null;
            if (source instanceof DOMSource) {
                Node nd = ((DOMSource)source).getNode();
                if (nd instanceof Document) {
                    nd = ((Document)nd).getDocumentElement();
                }
                return ((Element)nd).getLocalName();
            } else if (source instanceof StaxSource) {
                StaxSource s = (StaxSource)source;
                r = s.getXMLStreamReader();
            } else if (source instanceof StAXSource) {
                StAXSource s = (StAXSource)source;
                r = s.getXMLStreamReader();
            } else if (source instanceof StreamSource || source instanceof SAXSource) {
                //flip to stax so we can get the name
                r = StaxUtils.createXMLStreamReader(source);
                StaxSource src2 = new StaxSource(r);
                sources.set(i, src2);
            }
            if (r != null) {
                try {
                    if (r.getEventType() == XMLStreamConstants.START_DOCUMENT) {
                        r.next();
                    }
                    if (r.getEventType() != XMLStreamConstants.START_ELEMENT) {
                        r.nextTag();
                    }
                } catch (XMLStreamException e) {
                    //ignore
                    LOG.warn("Error finding the start element.", e);
                    return null;
                }
                return r.getLocalName();
            }
            return null;
        }
    }
    

    public List<Interceptor<? extends Message>> getOutFaultInterceptors() {
        return outFault;
    }

    public List<Interceptor<? extends Message>> getInFaultInterceptors() {
        return inFault;
    }

    public List<Interceptor<? extends Message>> getInInterceptors() {
        return in;
    }

    public List<Interceptor<? extends Message>> getOutInterceptors() {
        return out;
    }

    public void setInInterceptors(List<Interceptor<? extends Message>> interceptors) {
        in = interceptors;
    }

    public void setInFaultInterceptors(List<Interceptor<? extends Message>> interceptors) {
        inFault = interceptors;
    }

    public void setOutInterceptors(List<Interceptor<? extends Message>> interceptors) {
        out = interceptors;
    }

    public void setOutFaultInterceptors(List<Interceptor<? extends Message>> interceptors) {
        outFault = interceptors;
    }
    
    public void setFeatures(List<Feature> f) {
        features = f;
    }

    public List<Feature> getFeatures() {
        return features;
    }
    
    @SuppressWarnings("rawtypes")
    public void setHandlers(List<Handler> h) {
        handlers = h;
    }

    @SuppressWarnings("rawtypes")
    public List<Handler> getHandlers() {
        return handlers;
    }
    
    public void setSchemaLocations(List<String> sc) {
        schemaLocations = sc;
    }

    public List<String> getSchemaLocations() {
        return schemaLocations;
    }

    public String getTransportId() {
        return resolvePropertyPlaceholders(transportId);
    }

    public void setTransportId(String transportId) {
        this.transportId = transportId;
    }
    
    public String getBindingId() {
        return resolvePropertyPlaceholders(bindingId);
    }

    public void setBindingId(String bindingId) {
        this.bindingId = bindingId;
    }
    
    public BindingConfiguration getBindingConfig() {
        return bindingConfig;
    }

    public boolean isSkipFaultLogging() {
        return skipFaultLogging;
    }

    public void setSkipFaultLogging(boolean skipFaultLogging) {
        this.skipFaultLogging = skipFaultLogging;
    }

    public Boolean getMergeProtocolHeaders() {
        return mergeProtocolHeaders;
    }

    public void setMergeProtocolHeaders(boolean mergeProtocolHeaders) {
        this.mergeProtocolHeaders = mergeProtocolHeaders;
    }

    public void setBindingConfig(BindingConfiguration bindingConfig) {
        this.bindingConfig = bindingConfig;
    }

    public DataBinding getDataBinding() {
        return dataBinding;
    }

    public void setDataBinding(DataBinding dataBinding) {
        this.dataBinding = dataBinding;
    }

    public Object getServiceFactoryBean() {
        return serviceFactoryBean;
    }

    public void setServiceFactoryBean(Object serviceFactoryBean) {
        this.serviceFactoryBean = serviceFactoryBean;
    }

    public CxfEndpointConfigurer getCxfEndpointConfigurer() {
        return configurer;
    }

    public void setCxfEndpointConfigurer(CxfEndpointConfigurer configurer) {
        this.configurer = configurer;
    }

    public long getContinuationTimeout() {
        return continuationTimeout;
    }

    public void setContinuationTimeout(long continuationTimeout) {
        this.continuationTimeout = continuationTimeout;
    }

    
}
