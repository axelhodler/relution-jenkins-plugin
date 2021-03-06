/*
 * Copyright (c) 2013-2015 M-Way Solutions GmbH
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jenkinsci.plugins.relution_publisher.net.requests;

import org.apache.http.HttpEntity;
import org.apache.http.HttpEntityEnclosingRequest;
import org.apache.http.HttpException;
import org.apache.http.HttpHost;
import org.apache.http.HttpRequest;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.utils.URIUtils;
import org.apache.http.entity.BasicHttpEntity;
import org.apache.http.entity.ContentType;
import org.apache.http.nio.ContentEncoder;
import org.apache.http.nio.ContentEncoderChannel;
import org.apache.http.nio.FileContentEncoder;
import org.apache.http.nio.IOControl;
import org.apache.http.nio.protocol.HttpAsyncRequestProducer;
import org.apache.http.protocol.HttpContext;
import org.apache.tika.Tika;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.Charset;
import java.util.UUID;


public class ZeroCopyFileRequestProducer implements HttpAsyncRequestProducer {

    private final static String  CHARSET_NAME = "UTF-8";
    private final static Charset CHARSET      = Charset.forName(CHARSET_NAME);

    private final static String CONTENT_TYPE_MULTIPART_FORM_DATA = "multipart/form-data; boundary=%s";
    private final static String CRLF                             = "\r\n";

    private final String mMultipartBoundary = UUID.randomUUID().toString();

    private byte[] mMultipartHeader;
    private int    mMultipartHeaderIndex;

    private byte[] mMultipartFooter;
    private int    mMultipartFooterIndex;

    private final ZeroCopyFileRequest mRequest;

    private final File             mFile;
    private final RandomAccessFile mAccessfile;

    private FileChannel mFileChannel;
    private long        mIndexFile = -1;

    public ZeroCopyFileRequestProducer(final ZeroCopyFileRequest request) throws FileNotFoundException {

        this.mRequest = request;

        this.mFile = request.getFile();
        this.mAccessfile = new RandomAccessFile(this.mFile, "r");
    }

    private void closeChannel() throws IOException {
        if (this.mFileChannel != null) {
            this.mFileChannel.close();
            this.mFileChannel = null;
        }
    }

    private String getContentType(final File file) {

        try {
            final Tika tika = new Tika();
            return tika.detect(file);

        } catch (final IOException e) {
            return ContentType.DEFAULT_BINARY.toString();
        }
    }

    private byte[] getHeader() {

        if (this.mMultipartHeader == null) {
            final StringBuilder sb = new StringBuilder();
            this.writeln(sb, "--%s", this.mMultipartBoundary);
            this.writeln(sb, "Content-Disposition: form-data; name=\"%s\"; filename=\"%s\"", "file", this.mFile.getName());

            final String contentType = this.getContentType(this.mFile);
            this.writeln(sb, "Content-Type: %s", contentType);

            this.writeln(sb, "Content-Transfer-Encoding: binary");
            this.writeln(sb);

            final String value = sb.toString();
            this.mMultipartHeader = value.getBytes(CHARSET);
        }
        return this.mMultipartHeader;
    }

    private byte[] getFooter() {

        if (this.mMultipartFooter == null) {
            final StringBuilder sb = new StringBuilder();

            this.writeln(sb);
            this.writeln(sb, "--%s--", this.mMultipartBoundary);

            final String value = sb.toString();
            this.mMultipartFooter = value.getBytes(CHARSET);
        }
        return this.mMultipartFooter;
    }

    private boolean writeHeader(final ContentEncoder encoder, final IOControl ioctrl) throws IOException {

        final byte[] array = this.getHeader();

        if (this.mMultipartHeaderIndex >= array.length) {
            return true;
        }

        final int length = array.length - this.mMultipartHeaderIndex;
        final ByteBuffer buffer = ByteBuffer.wrap(array, this.mMultipartHeaderIndex, length);
        this.mMultipartHeaderIndex += encoder.write(buffer);

        return false;
    }

    private boolean writeFooter(final ContentEncoder encoder, final IOControl ioctrl) throws IOException {

        final byte[] array = this.getFooter();

        if (this.mMultipartFooterIndex >= array.length) {
            return true;
        }

        final int length = array.length - this.mMultipartFooterIndex;
        final ByteBuffer buffer = ByteBuffer.wrap(array, this.mMultipartFooterIndex, length);
        this.mMultipartFooterIndex += encoder.write(buffer);

        return false;
    }

    private void writeln(final StringBuilder sb, final String value, final Object... args) {

        final String data = String.format(value, args);

        sb.append(data);
        sb.append(CRLF);
    }

    private void writeln(final StringBuilder sb) {
        sb.append(CRLF);
    }

    protected HttpEntityEnclosingRequest createRequest(final String uri, final HttpEntity entity) {

        final HttpPost request = new HttpPost(uri);
        request.setEntity(entity);

        this.mRequest.addHeaders(request);
        return request;
    }

    public String getContentType() {
        return String.format(CONTENT_TYPE_MULTIPART_FORM_DATA, this.mMultipartBoundary);
    }

    public long getContentLength() {
        final byte[] header = this.getHeader();
        final byte[] footer = this.getFooter();

        return header.length + this.mFile.length() + footer.length;
    }

    @Override
    public HttpRequest generateRequest() throws IOException, HttpException {
        final BasicHttpEntity entity = new BasicHttpEntity();

        entity.setContentLength(this.getContentLength());
        entity.setContentType(this.getContentType());
        entity.setChunked(false);

        return this.createRequest(this.mRequest.getUri(), entity);
    }

    @Override
    public synchronized void produceContent(final ContentEncoder encoder, final IOControl ioctrl)
            throws IOException {

        if (!this.writeHeader(encoder, ioctrl)) {
            return;
        }

        if (this.mFileChannel == null) {
            this.mFileChannel = this.mAccessfile.getChannel();
            this.mIndexFile = 0;
        }

        final long transferred;

        if (encoder instanceof FileContentEncoder) {
            transferred = ((FileContentEncoder) encoder).transfer(this.mFileChannel, this.mIndexFile, Integer.MAX_VALUE);

        } else {
            transferred = this.mFileChannel.transferTo(this.mIndexFile, Integer.MAX_VALUE, new ContentEncoderChannel(encoder));

        }

        if (transferred > 0) {
            this.mIndexFile += transferred;
        }

        if (this.mIndexFile >= this.mFileChannel.size()) {
            if (this.writeFooter(encoder, ioctrl)) {
                this.closeChannel();
                encoder.complete();
            }
        }
    }

    @Override
    public void requestCompleted(final HttpContext context) {
    }

    @Override
    public void failed(final Exception ex) {
    }

    @Override
    public boolean isRepeatable() {
        return true;
    }

    @Override
    public synchronized void resetRequest() throws IOException {
        this.mMultipartHeaderIndex = 0;
        this.mMultipartFooterIndex = 0;
        this.closeChannel();
    }

    @Override
    public HttpHost getTarget() {
        final URI uri = URI.create(this.mRequest.getUri());
        return URIUtils.extractHost(uri);
    }

    @Override
    public synchronized void close() throws IOException {

        try {
            this.mAccessfile.close();
        } catch (final IOException e) {
            // do nothing
        }
    }
}
