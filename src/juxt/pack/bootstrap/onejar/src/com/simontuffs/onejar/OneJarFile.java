/*
 * Copyright (c) 2004-2010, P. Simon Tuffs (simon@simontuffs.com)
 * All rights reserved.
 * 
 * See the full license at http://one-jar.sourceforge.net/one-jar-license.html
 * This license is also included in the distributions of this software
 * under doc/one-jar-license.txt
 * 
 * Many thanks to the following for their contributions to One-Jar:
 * Contributor: sebastian : http://code.google.com/u/@WBZRRlBYBxZHXQl9/ 
 *   Original creator of the OneJarFile/OneJarUrlConnecion solution to resource location
 *   using jar protocols.
 *                   
 */
    
package com.simontuffs.onejar;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Enumeration;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarInputStream;
import java.util.zip.ZipEntry;

public class OneJarFile extends JarFile {

    protected final String jarFilename;
    protected final String filename;
    protected final JarEntry wrappedJarFile;

    public OneJarFile(String myJarPath, String jarFilename, String filename) throws IOException {
        super(myJarPath);
        this.jarFilename = jarFilename;
        this.filename = filename;
        wrappedJarFile = super.getJarEntry(this.jarFilename);
    }

    public JarEntry getJarEntry(String name) {
        String filename = name.substring(name.indexOf("!/") + 2);
        if (filename.equals(MANIFEST_NAME)) {
            // Synthesize a JarEntry.
            return new JarEntry(filename) { 
            };
        }
        try {
            JarInputStream is = new JarInputStream(super.getInputStream(wrappedJarFile));
            try {
                JarEntry entry;
                while ((entry = is.getNextJarEntry()) != null) {
                    if (entry.getName().equals(filename)) {
                        return entry;
                    }
                }
            } finally {
                is.close();
            }
        } catch (IOException e) {
            throw new IllegalStateException("Undefined Error", e);
        }
        return null;
        // throw new RuntimeException("Entry not found : " + name);
    }

    public Enumeration entries() {
        try {
            final JarInputStream is = new JarInputStream(super.getInputStream(wrappedJarFile));
            return new Enumeration() {

                protected JarEntry next;

                public Object nextElement() {
                    if (next != null) {
                        JarEntry tmp = next;
                        next = null;
                        return tmp;
                    }

                    try {
                        return is.getNextJarEntry();
                    } catch (IOException e) {
                        throw new RuntimeException("Undefined Error", e);
                    }
                }

                public boolean hasMoreElements() {
                    if (next != null) {
                        return true;
                    }
                    try {
                        next = is.getNextJarEntry();
                        if (next == null) {
                            is.close();
                        }
                    } catch (IOException e) {
                        throw new RuntimeException("Undefined Error", e);
                    }
                    return next != null;
                }
            };
        } catch (IOException e) {
            throw new RuntimeException("Undefined Error", e);
        }
    }

    public synchronized InputStream getInputStream(ZipEntry ze) throws IOException {
        if (ze == null)
            return null;
        try {
            JarInputStream is = new JarInputStream(super.getInputStream(wrappedJarFile));
            if (filename.equals(MANIFEST_NAME)) {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                is.getManifest().write(baos);
                return new ByteArrayInputStream(baos.toByteArray());
            }
            try {
                JarEntry entry;
                while ((entry = is.getNextJarEntry()) != null) {
                    if (entry.getName().equals(ze.getName())) {
                        ByteArrayOutputStream baos = new ByteArrayOutputStream();
                        copy(is, baos);
                        return new ByteArrayInputStream(baos.toByteArray());
                    }
                }
            } finally {
                is.close();
            }
        } catch (IOException e) {
            throw new RuntimeException("Undefined Error", e);
        }

        throw new RuntimeException("Entry not found : " + ze.getName());
    }

    protected void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[1024];
        while (true) {
            int len = in.read(buf);
            if (len < 0)
                break;
            out.write(buf, 0, len);
        }
    }

}
