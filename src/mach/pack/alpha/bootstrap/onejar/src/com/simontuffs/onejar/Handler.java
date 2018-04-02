/*
 * Copyright (c) 2004-2010, P. Simon Tuffs (simon@simontuffs.com)
 * All rights reserved.
 *
 * See the full license at http://one-jar.sourceforge.net/one-jar-license.html
 * This license is also included in the distributions of this software
 * under doc/one-jar-license.txt
 */

package com.simontuffs.onejar;

import java.io.IOException;
import java.io.InputStream;
import java.net.FileNameMap;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;

/**
 * @author simon@simontuffs.com
 *
 */
public class Handler extends URLStreamHandler {

	/**
	 * This protocol name must match the name of the package in which this class
	 * lives.
	 */
	public static String PROTOCOL = "onejar";

	/** 
	 * @see java.net.URLStreamHandler#openConnection(java.net.URL)
	 */
	protected URLConnection openConnection(final URL u) throws IOException {
		final String resource = u.getPath();
		return new URLConnection(u) {
			public void connect() {
			}
            public String getContentType() {
                FileNameMap fileNameMap = java.net.URLConnection.getFileNameMap();
                String contentType = fileNameMap.getContentTypeFor(resource);
                if (contentType == null) 
                    contentType = "text/plain";
                return contentType;
            }
			public InputStream getInputStream() throws IOException {
				// Use the Boot classloader to get the resource.  There
				// is only one per one-jar.
				JarClassLoader cl = Boot.getClassLoader();
				InputStream is = cl.getByteStream(resource);
				// sun.awt image loading does not like null input streams returned here.
				// Throw IOException (probably better anyway).
				if (is == null) 
					throw new IOException("cl.getByteStream() returned null for " + resource);
				return is;
			}
		};
	}
    
}
