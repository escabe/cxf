/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.cxf.rs.security.jose.jaxrs;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Collections;
import java.util.zip.DeflaterOutputStream;

import javax.annotation.Priority;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.ext.WriterInterceptor;
import javax.ws.rs.ext.WriterInterceptorContext;

import org.apache.cxf.common.util.StringUtils;
import org.apache.cxf.helpers.IOUtils;
import org.apache.cxf.io.CachedOutputStream;
import org.apache.cxf.jaxrs.utils.JAXRSUtils;
import org.apache.cxf.rs.security.jose.JoseConstants;
import org.apache.cxf.rs.security.jose.JoseHeadersReaderWriter;
import org.apache.cxf.rs.security.jose.JoseHeadersWriter;
import org.apache.cxf.rs.security.jose.jwe.JweCompactProducer;
import org.apache.cxf.rs.security.jose.jwe.JweEncryptionProvider;
import org.apache.cxf.rs.security.jose.jwe.JweEncryptionState;
import org.apache.cxf.rs.security.jose.jwe.JweHeaders;
import org.apache.cxf.rs.security.jose.jwe.JweOutputStream;
import org.apache.cxf.rs.security.jose.jwe.JweUtils;

@Priority(Priorities.JWE_WRITE_PRIORITY)
public class JweWriterInterceptor implements WriterInterceptor {
    private JweEncryptionProvider encryptionProvider;
    private boolean contentTypeRequired = true;
    private boolean useJweOutputStream;
    private JoseHeadersWriter writer = new JoseHeadersReaderWriter();
    @Override
    public void aroundWriteTo(WriterInterceptorContext ctx) throws IOException, WebApplicationException {
        if (ctx.getEntity() == null) {
            ctx.proceed();
            return;
        }
        OutputStream actualOs = ctx.getOutputStream();
        
        JweEncryptionProvider theEncryptionProvider = getInitializedEncryptionProvider();
        
        String ctString = null;
        MediaType contentMediaType = ctx.getMediaType();
        if (contentTypeRequired && contentMediaType != null) {
            if ("application".equals(contentMediaType.getType())) {
                ctString = contentMediaType.getSubtype();
            } else {
                ctString = JAXRSUtils.mediaTypeToString(contentMediaType);
            }
        }
        
        if (useJweOutputStream) {
            JweEncryptionState encryption = theEncryptionProvider.createJweEncryptionState(toJweHeaders(ctString));
            try {
                JweCompactProducer.startJweContent(actualOs,
                                                   encryption.getHeaders(), 
                                                   writer, 
                                                   encryption.getContentEncryptionKey(), 
                                                   encryption.getIv());
            } catch (IOException ex) {
                throw new SecurityException(ex);
            }
            OutputStream wrappedStream = null;
            JweOutputStream jweOutputStream = new JweOutputStream(actualOs, encryption.getCipher(), 
                                                         encryption.getAuthTagProducer());
            wrappedStream = jweOutputStream;
            if (encryption.isCompressionSupported()) {
                wrappedStream = new DeflaterOutputStream(jweOutputStream);
            }
            
            ctx.setOutputStream(wrappedStream);
            ctx.proceed();
            setJoseMediaType(ctx);
            jweOutputStream.finalFlush();
        } else {
            CachedOutputStream cos = new CachedOutputStream(); 
            ctx.setOutputStream(cos);
            ctx.proceed();
            String jweContent = theEncryptionProvider.encrypt(cos.getBytes(), toJweHeaders(ctString));
            setJoseMediaType(ctx);
            IOUtils.copy(new ByteArrayInputStream(StringUtils.toBytesUTF8(jweContent)), 
                         actualOs);
            actualOs.flush();
        }
    }
    
    private void setJoseMediaType(WriterInterceptorContext ctx) {
        MediaType joseMediaType = JAXRSUtils.toMediaType(JoseConstants.MEDIA_TYPE_JOSE);
        ctx.setMediaType(joseMediaType);
    }
    
    protected JweEncryptionProvider getInitializedEncryptionProvider() {
        if (encryptionProvider != null) {
            return encryptionProvider;    
        } 
        return JweUtils.loadEncryptionProvider(true);
    }
    
    public void setUseJweOutputStream(boolean useJweOutputStream) {
        this.useJweOutputStream = useJweOutputStream;
    }

    public void setWriter(JoseHeadersWriter writer) {
        this.writer = writer;
    }

    public void setEncryptionProvider(JweEncryptionProvider encryptionProvider) {
        this.encryptionProvider = encryptionProvider;
    }
    private static JweHeaders toJweHeaders(String ct) {
        return new JweHeaders(Collections.<String, Object>singletonMap(JoseConstants.HEADER_CONTENT_TYPE, ct));
    }
}
