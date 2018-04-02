/*
 * Copyright (c) 2004-2010, P. Simon Tuffs (simon@simontuffs.com)
 * All rights reserved.
 *
 * See the full license at http://one-jar.sourceforge.net/one-jar-license.html
 * This license is also included in the distributions of this software
 * under doc/one-jar-license.txt
 */

/**
 * Many thanks to the following for their contributions to One-Jar:
 * 
 * Contributor: Christopher Ottley <xknight@users.sourceforge.net>
 * Contributor: Thijs Sujiten (www.semantica.nl)
 * Contributor: Gerold Friedmann
 */

package com.simontuffs.onejar;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarInputStream;
import java.util.jar.Manifest;
import java.util.jar.Attributes.Name;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Loads classes from pre-defined locations inside the jar file containing this
 * class.  Classes will be loaded from jar files contained in the following 
 * locations within the main jar file (on the classpath of the application 
 * actually, which when running with the "java -jar" command works out to be
 * the same thing).
 * <ul>
 * <li>
 *   /lib	Used to contain library jars.
 * </li>
 * <li>
 *   /main	Used to contain a default main jar.
 * </li>
 * </ul> 
 * @author simon@simontuffs.com (<a href="http://www.simontuffs.com">http://www.simontuffs.com</a>)
 */
public class JarClassLoader extends ClassLoader implements IProperties {
    
    public final static String LIB_PREFIX = "lib/";
    public final static String BINLIB_PREFIX = "binlib/";
    public final static String MAIN_PREFIX = "main/";
    public final static String RECORDING = "recording";
    public final static String TMP = "tmp";
    public final static String UNPACK = "unpack";
    public final static String EXPAND = "One-Jar-Expand";
    public final static String EXPAND_DIR = "One-Jar-Expand-Dir";
    public final static String SHOW_EXPAND = "One-Jar-Show-Expand";
    public final static String CONFIRM_EXPAND = "One-Jar-Confirm-Expand";
    public final static String CLASS = ".class";
    
    public final static String NL = System.getProperty("line.separator");
    
    public final static String JAVA_PROTOCOL_HANDLER = "java.protocol.handler.pkgs";
    
    protected String name;
    protected boolean noExpand, expanded;
    protected ClassLoader externalClassLoader;
    
    static {
        // Add our 'onejar:' protocol handler, but leave open the 
        // possibility of a subsequent class taking over the 
        // factory.  TODO: (how reasonable is this?)
        String handlerPackage = System.getProperty(JAVA_PROTOCOL_HANDLER);
        if (handlerPackage == null) handlerPackage = "";
        if (handlerPackage.length() > 0) handlerPackage = "|" + handlerPackage;
        handlerPackage = "com.simontuffs" + handlerPackage;
        System.setProperty(JAVA_PROTOCOL_HANDLER, handlerPackage);
        
    }
    
    protected String PREFIX() {
        return "JarClassLoader: ";
    }
    
    protected String NAME() {
        return (name != null? "'" + name + "' ": "");
    }
    
    protected void VERBOSE(String message) {
        if (verbose) System.out.println(PREFIX() + NAME() + message);
    }
    
    protected void WARNING(String message) {
        if (warning) System.err.println(PREFIX() + "Warning: " + NAME() + message); 
    }
    
    protected void INFO(String message) {
        if (info) System.out.println(PREFIX() + "Info: " + NAME() + message);
    }
    
    protected void PRINTLN(String message) {
        System.out.println(message);
    }
    
    protected void PRINT(String message) {
        System.out.print(message);
    }
    
    // Synchronize for thread safety.  This is less important until we
    // start to do lazy loading, but it's a good idea anyway.
    protected Map byteCode = Collections.synchronizedMap(new HashMap());
    protected Map pdCache = Collections.synchronizedMap(new HashMap());
    protected Map binLibPath = Collections.synchronizedMap(new HashMap());
    protected Set jarNames = Collections.synchronizedSet(new HashSet());
    
    protected boolean record = false, flatten = false, unpackFindResource = false;
    protected boolean verbose = false, info = false, warning = true;
    protected String recording = RECORDING;
    
    protected String jarName, mainJar, wrapDir;
    protected boolean delegateToParent;
    
    protected static class ByteCode {
		public ByteCode(String $name, String $original, ByteArrayOutputStream baos, String $codebase, Manifest $manifest) {
            name = $name;
            original = $original;
            bytes = baos.toByteArray();
            codebase = $codebase;
			manifest = $manifest;
        }
        public byte bytes[];
        public String name, original, codebase;
		public Manifest manifest;
    }
    
    
    /**
     * Create a non-delegating but jar-capable classloader for bootstrap
     * purposes.
     * @param $wrap  The directory in the archive from which to load a 
     * wrapping classloader.
     */
    public JarClassLoader(String $wrap) {
        wrapDir = $wrap;
        delegateToParent = wrapDir == null;
        Boot.setProperties(this);
        init();
    }
    
    /**
     * The main constructor for the Jar-capable classloader.
     * @param $record	If true, the JarClassLoader will record all used classes
     * 					into a recording directory (called 'recording' by default)
     *				 	The name of each jar file will be used as a directory name
     *					for the recorded classes.
     * @param $flatten  Whether to flatten out the recorded classes (i.e. eliminate
     * 					the jar-file name from the recordings).
     * 
     * Example: Given the following layout of the one-jar.jar file
     * <pre>
     *    /
     *    /META-INF
     *    | MANIFEST.MF
     *    /com
     *      /simontuffs
     *        /onejar
     *          Boot.class
     *          JarClassLoader.class
     *    /main
     *        main.jar
     *        /com
     *          /main
     *            Main.class 
     *    /lib
     *        util.jar
     *          /com
     *            /util
     *              Util.clas
     * </pre>
     * The recording directory will look like this:
     * <ul>
     * <li>flatten=false</li>
     * <pre>
     *   /recording
     *     /main.jar
     *       /com
     *         /main
     *            Main.class
     *     /util.jar
     *       /com
     *         /util
     *            Util.class
     * </pre>
     *
     * <li>flatten = true</li>
     * <pre>
     *   /recording
     *     /com
     *       /main
     *          Main.class
     *       /util
     *          Util.class
     *   
     * </ul>
     * Flatten mode is intended for when you want to create a super-jar which can
     * be launched directly without using one-jar's launcher.  Run your application
     * under all possible scenarios to collect the actual classes which are loaded,
     * then jar them all up, and point to the main class with a "Main-Class" entry
     * in the manifest.  
     *       
     */
    public JarClassLoader(ClassLoader parent) {
        super(parent);
        delegateToParent = true;
        Boot.setProperties(this);
        init();
        // System.out.println(PREFIX() + this + " parent=" + parent + " loaded by " + this.getClass().getClassLoader());
    }
    
    protected static ThreadLocal current = new ThreadLocal();
    /**
     * Common initialization code: establishes a classloader for delegation
     * to one-jar.class.path resources.
     */
    protected void init() {
        String classpath = System.getProperty(Boot.P_ONE_JAR_CLASS_PATH);
        if (classpath != null) {
            String tokens[] = classpath.split("\\" + Boot.P_PATH_SEPARATOR);
            List list = new ArrayList();
            for (int i=0; i<tokens.length; i++) {
                String path = tokens[i];
                try {
                    list.add(new URL(path));
                } catch (MalformedURLException mux) {
                    // Try a file:// prefix and an absolute path.
                    try {
                    	String _path = new File(path).getCanonicalPath();
                    	// URLClassLoader searches in a directory if and only if the path ends with '/':
                    	// toURI() takes care of adding the trailing slash in this case so everything's ok
                        list.add(new File(_path).toURI().toURL());
                    } catch (MalformedURLException ignore) {
                        Boot.WARNING("Unable to parse external path: " + path);
                    } catch (IOException ignore) {
                    	Boot.WARNING("Unable to parse external path: " + path);                   
                    } catch (IllegalArgumentException ignore) {
                    	// won't happen File.toURI() returns an absolute URI
                    	Boot.WARNING("Unable to parse external path: " + path);
                    }
                }
            }
            URL urls[] = (URL[])list.toArray(new URL[0]);
            Boot.INFO("external URLs=" + Arrays.asList(urls));
            // BUG-2833948
            // Delegate back into this classloader, use ThreadLocal to avoid recursion.
            externalClassLoader = new URLClassLoader(urls, this) {
                // Handle recursion for classes, and mutual recursion for resources.
                final static String LOAD_CLASS = "loadClass():";
                final static String GET_RESOURCE = "getResource():";
                final static String FIND_RESOURCE = "findResource():";
                // Protect entry points which could lead to recursion.  Strangely
                // inelegant because you can't proxy a class.  Or use closures.
                public Class loadClass(String name) throws ClassNotFoundException {
                    if (reentered(LOAD_CLASS + name)) {
                        throw new ClassNotFoundException(name);
                    }
                    VERBOSE("externalClassLoader.loadClass(" + name + ")");
                    Object old = current.get();
                    current.set(LOAD_CLASS + name);
                    try {
                        return super.loadClass(name);
                    } finally {
                        current.set(old);
                    }
                }
                public URL getResource(String name) {
                    if (reentered(GET_RESOURCE + name))
                        return null;
                    VERBOSE("externalClassLoader.getResource(" + name + ")");
                    Object old = current.get();
                    current.set(GET_RESOURCE + name);
                    try {
                        return super.getResource(name);
                    } finally {
                        current.set(old);
                    }
                }
                public URL findResource(String name) {
                    if (reentered(FIND_RESOURCE + name)) 
                        return null;
                    VERBOSE("externalClassLoader.findResource(" + name + ")");
                    Object old = current.get();
                    current.set(name);
                    try {
                        current.set(FIND_RESOURCE + name);
                        return super.findResource(name);
                    } finally {
                        current.set(old);
                    }
                }
                protected boolean reentered(String name) {
                    // Defend against null name: not sure about semantics there.
                    Object old = current.get();
                    return old != null && old.equals(name);
                }
            };
        }
    }
    
    public String load(String mainClass) {
        // Hack: if there is a one-jar.jarname property, use it.
        String jarname = Boot.getMyJarPath();
        return load(mainClass, jarname);
    }
    
    public String load(String mainClass, String jarName) {
    	VERBOSE("load("+mainClass+","+jarName+")");
        if (record) {
            new File(recording).mkdirs();
        }
        try {
            if (jarName == null) {
                jarName = Boot.getMyJarPath();
            }
            JarFile jarFile = new JarFile(jarName);
            Enumeration _enum = jarFile.entries();
            Manifest manifest = jarFile.getManifest();
            String expandPaths[] = null;
            // TODO: Allow a destination directory (relative or absolute) to 
            // be specified like this:
            // One-Jar-Expand: build=../expanded
            String expand = manifest.getMainAttributes().getValue(EXPAND);
            String expanddir = System.getProperty(Boot.P_EXPAND_DIR);
            if (expanddir == null) {
            	expanddir = manifest.getMainAttributes().getValue(EXPAND_DIR);
            }
            // Default is to expand into temporary directory based on the name of the jar file.
            if (expanddir == null) {
            	String jar = new File(jarName).getName().replaceFirst("\\.[^\\.]*$", "");
            	expanddir = "${java.io.tmpdir}/" + jar;
            }
            // Expand system properties.
            expanddir = replaceProps(System.getProperties(), expanddir);
            
            // Make a note of this location in the VM system properties in case applications need to know
            // where the expanded files are.
            System.setProperty(Boot.P_EXPAND_DIR, expanddir);
            
            boolean shouldExpand = true;
            File tmpdir = new File(expanddir);
            if (noExpand == false && expand != null) {
                expanded = true;
                VERBOSE(EXPAND + "=" + expand);
                expandPaths = expand.split(",");
                boolean getconfirm = Boolean.TRUE.toString().equals(manifest.getMainAttributes().getValue(CONFIRM_EXPAND));
                if (getconfirm) {
                    String answer = getConfirmation(tmpdir);
                    if (answer == null) answer = "n";
                    answer = answer.trim().toLowerCase();
                    if (answer.startsWith("q")) {
                        PRINTLN("exiting without expansion.");
                        // Indicate (expected) failure with a non-zero return code.
                        System.exit(1);
                    } else if (answer.startsWith("n")) {
                        shouldExpand = false;
                    }
                }
            }
            boolean showexpand = Boolean.TRUE.toString().equals(manifest.getMainAttributes().getValue(SHOW_EXPAND));
            if (showexpand) {
                PRINTLN("Expanding to: " + tmpdir.getAbsolutePath());
            }
            while (_enum.hasMoreElements()) {
                JarEntry entry = (JarEntry)_enum.nextElement();
                if (entry.isDirectory()) continue;
                
                // The META-INF/MANIFEST.MF file can contain a property which names
                // directories in the JAR to be expanded (comma separated). For example:
                // One-Jar-Expand: build,tmp,webapps
                String $entry = entry.getName();
                if (expandPaths != null) {
                    // TODO: Can't think of a better way to do this right now.  
                    // This code really doesn't need to be optimized anyway.
                    if (shouldExpand && shouldExpand(expandPaths, $entry)) {
                        File dest = new File(tmpdir, $entry);
                        // Override if ZIP file is newer than existing.
                        if (!dest.exists() || dest.lastModified() < entry.getTime()) {
                            String msg = "Expanding:  " + $entry;
                            if (showexpand) {
                                PRINTLN(msg);
                            } else {
                                INFO(msg);
                            }
                            if (dest.exists()) INFO("Update because lastModified=" + new Date(dest.lastModified()) + ", entry=" + new Date(entry.getTime()));
                            File parent = dest.getParentFile();
                            if (parent != null) {
                                parent.mkdirs();
                            }
                            VERBOSE("using jarFile.getInputStream(" + entry + ")");
                            InputStream is = jarFile.getInputStream(entry);
                            FileOutputStream os = new FileOutputStream(dest); 
                            copy(is, os);
                            is.close();
                            os.close();
                        } else {
                            String msg = "Up-to-date: " + $entry;
                            if (showexpand) {
                                PRINTLN(msg);
                            } else {
                                VERBOSE(msg);
                            }
                        }
                    }
                }
                
                if (wrapDir != null && $entry.startsWith(wrapDir) || $entry.startsWith(LIB_PREFIX) || $entry.startsWith(MAIN_PREFIX)) {
                    if (wrapDir != null && !entry.getName().startsWith(wrapDir)) continue;
                    // Load it! 
                    VERBOSE("caching " + $entry);
                    VERBOSE("using jarFile.getInputStream(" + entry + ")");
                    {
                        // Note: loadByteCode consumes the input stream, so make sure its scope
                        // does not extend beyond here.
                        InputStream is = jarFile.getInputStream(entry);
                        if (is == null) 
                            throw new IOException("Unable to load resource /" + $entry + " using " + this);
						loadByteCode(is, $entry, null);
                    }
                    
                    // Do we need to look for a main class?
                    if ($entry.startsWith(MAIN_PREFIX)) {
                        if (mainClass == null) {
                            JarInputStream jis = new JarInputStream(jarFile.getInputStream(entry));
                            Manifest m = jis.getManifest();
                            jis.close();
                            // Is this a jar file with a manifest?
                            if (m != null) {
                                mainClass = jis.getManifest().getMainAttributes().getValue(Attributes.Name.MAIN_CLASS);
                                mainJar = $entry;
                            }
                        } else if (mainJar != null) {
                            WARNING("A main class is defined in multiple jar files inside " + MAIN_PREFIX + mainJar + " and " + $entry);
                            WARNING("The main class " + mainClass + " from " + mainJar + " will be used");
                        }
                    } 
                } else if (wrapDir == null && $entry.startsWith(UNPACK)) {
                    // Unpack into a temporary directory which is on the classpath of
                    // the application classloader.  Badly designed code which relies on the
                    // application classloader can be made to work in this way.
                    InputStream is = this.getClass().getResourceAsStream("/" + $entry);
                    if (is == null) throw new IOException($entry);
                    // Make a sentinel.
                    File dir = new File(TMP);
                    File sentinel = new File(dir, $entry.replace('/', '.'));
                    if (!sentinel.exists()) {
                        INFO("unpacking " + $entry + " into " + dir.getCanonicalPath());
						loadByteCode(is, $entry, TMP);
                        sentinel.getParentFile().mkdirs();
                        sentinel.createNewFile();
                    }
                } else if ($entry.endsWith(CLASS)) {
                    // A plain vanilla class file rooted at the top of the jar file.
					loadBytes(entry, jarFile.getInputStream(entry), "/", null, manifest);
                    VERBOSE("One-Jar class: " + jarFile.getName() + "!/" + entry.getName());
                } else {
                    // A resource? 
                    loadBytes(entry, jarFile.getInputStream(entry), "/", null, manifest);
                    VERBOSE("One-Jar resource: " + jarFile.getName() + "!/" + entry.getName());
                }
            }
            // If mainClass is still not defined, return null.  The caller is then responsible
            // for determining a main class.
            
        } catch (IOException iox) {
            System.err.println("Unable to load resource: " + iox);
            iox.printStackTrace(System.err);
        }
        return mainClass;
    }
    
    public String replaceProps(Map replace, String string) {
		Pattern pat = Pattern.compile("\\$\\{([^\\}]*)");
		Matcher mat = pat.matcher(string);
		boolean found = mat.find();
		Map props = new HashMap();
		while (found) {
			String prop = mat.group(1);
			props.put(prop, replace.get(prop));
			found = mat.find();
		}
		Set keys = props.keySet();
		Iterator iter = props.keySet().iterator();
		while (iter.hasNext()) {
			String prop = (String)iter.next();
			string = string.replace("${" + prop + "}", (String)props.get(prop));
		}
		return string;
    }

    public static boolean shouldExpand(String expandPaths[], String name) {
        for (int i=0; i<expandPaths.length; i++) {
            if (name.startsWith(expandPaths[i])) return true;
        }
        return false;
    }        
    
	protected void loadByteCode(InputStream is, String jar, String tmp) throws IOException {
        JarInputStream jis = new JarInputStream(is);
        JarEntry entry = null;
        // TODO: implement lazy loading of bytecode.
        Manifest manifest = jis.getManifest();
        if (manifest == null) {
            WARNING("Null manifest from input stream associated with: " + jar);
        }
        while ((entry = jis.getNextJarEntry()) != null) {
            // if (entry.isDirectory()) continue;
            loadBytes(entry, jis, jar, tmp, manifest);
        }
        // Add in a fake manifest entry.
        if (manifest != null) {
            entry = new JarEntry(Boot.MANIFEST);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            manifest.write(baos);
            ByteArrayInputStream bais = new ByteArrayInputStream(baos.toByteArray()); 
            loadBytes(entry, bais, jar, tmp, manifest);
        }

    }
	
	protected void loadBytes(JarEntry entry, InputStream is, String jar, String tmp, Manifest man) throws IOException {
        String entryName = entry.getName();
        int index = entryName.lastIndexOf('.');
        String type = entryName.substring(index+1);
        
        // agattung: patch (for one-jar 0.95)
        // add package handling to avoid NullPointer exceptions
        // after calls to getPackage method of this ClassLoader
        int index2 = entryName.lastIndexOf('/', index-1);
        if (entryName.endsWith(CLASS) && index2 > -1) {
            String packageName = entryName.substring(0, index2).replace('/', '.');
            if (getPackage(packageName) == null) {
                // Defend against null manifest.
                if (man != null) {
                    definePackage(packageName, man, urlFactory.getCodeBase(jar));
                } else {
                    definePackage(packageName, null, null, null, null, null, null, null);
                }
            }
        }
        // end patch
        
        // Because we are doing stream processing, we don't know what
        // the size of the entries is.  So we store them dynamically.
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        copy(is, baos);
        
        if (tmp != null) {
            // Unpack into a temporary working directory which is on the classpath.
            File file = new File(tmp, entry.getName());
            file.getParentFile().mkdirs();
            FileOutputStream fos = new FileOutputStream(file);
            fos.write(baos.toByteArray());
            fos.close();
            
        } else {
            // If entry is a class, check to see that it hasn't been defined
            // already.  Class names must be unique within a classloader because
            // they are cached inside the VM until the classloader is released.
            if (type.equals("class")) {
                if (alreadyCached(entryName, jar, baos)) return;
				byteCode.put(entryName, new ByteCode(entryName, entry.getName(), baos, jar, man));
                VERBOSE("cached bytes for class " + entryName);
            } else {
                // Another kind of resource.  Cache this by name, and also prefixed
                // by the jar name.  Don't duplicate the bytes.  This allows us
                // to map resource lookups to either jar-local, or globally defined.
                String localname = jar + "/" + entryName;
				byteCode.put(localname, new ByteCode(localname, entry.getName(), baos, jar, man));
                // Keep a set of jar names so we can do multiple-resource lookup by name
                // as in findResources().
                jarNames.add(jar);
                VERBOSE("cached bytes for local name " + localname);
                // Only keep the first non-local entry: this is like classpath where the first
                // to define wins.  
                if (alreadyCached(entryName, jar, baos)) return;

                byteCode.put(entryName, new ByteCode(entryName, entry.getName(), baos, jar, man));
                VERBOSE("cached bytes for entry name " + entryName);
                
            }
        }
    }
    
	/**
	 * Override to ensure that this classloader is the thread context classloader
	 * when used to load a class.  Avoids subtle, nasty problems.
	 * 
	 */
	public Class loadClass(String name, boolean resolve) throws ClassNotFoundException {
        // Set the context classloader in case any classloaders delegate to it.
        // Otherwise it would default to the sun.misc.Launcher$AppClassLoader which
        // is used to launch the jar application, and attempts to load through
        // it would fail if that code is encapsulated inside the one-jar.
        Thread.currentThread().setContextClassLoader(this);
	    return super.loadClass(name, resolve);
	}
	
    /**
     * Locate the named class in a jar-file, contained inside the
     * jar file which was used to load <u>this</u> class.
     */
    protected Class findClass(String name) throws ClassNotFoundException {
        // Delegate to external paths first
        Class cls = null;
        if (externalClassLoader != null) {
            try {
                return externalClassLoader.loadClass(name);
            } catch (ClassNotFoundException cnfx) {
                // continue...
            }
        }

        // Make sure not to load duplicate classes.
        cls = findLoadedClass(name);
        if (cls != null) return cls;
        
        // Look up the class in the byte codes.
        // Translate path?
        VERBOSE("findClass(" + name + ")");
        String cache = name.replace('.', '/') + CLASS;
        ByteCode bytecode = (ByteCode)byteCode.get(cache);
        if (bytecode != null) {
            VERBOSE("found " + name + " in codebase '" + bytecode.codebase + "'");
            if (record) {
                record(bytecode);
            }
            // Use a protectionDomain to associate the codebase with the
            // class.
            ProtectionDomain pd = (ProtectionDomain)pdCache.get(bytecode.codebase);
            if (pd == null) {
                try {
                    URL url = urlFactory.getCodeBase(bytecode.codebase);
                    
                    CodeSource source = new CodeSource(url, (Certificate[])null);
                    pd = new ProtectionDomain(source, null, this, null);
                    pdCache.put(bytecode.codebase, pd);
                } catch (MalformedURLException mux) {
                    throw new ClassNotFoundException(name, mux);
                }
            }
            
            // Do it the simple way.
            byte bytes[] = bytecode.bytes;
			
			int i = name.lastIndexOf('.');
			if (i != -1) {
				String pkgname = name.substring(0, i);
				// Check if package already loaded.
				Package pkg = getPackage(pkgname);
				Manifest man = bytecode.manifest;
				if (pkg != null) {
					// Package found, so check package sealing.
					if (pkg.isSealed()) {
						// Verify that code source URL is the same.
						if (!pkg.isSealed(pd.getCodeSource().getLocation())) {
							throw new SecurityException("sealing violation: package " + pkgname + " is sealed");
						}

					} else {
						// Make sure we are not attempting to seal the package
						// at this code source URL.
						if ((man != null) && isSealed(pkgname, man)) {
							throw new SecurityException("sealing violation: can't seal package " + pkgname + ": already loaded");
						}
					}
				} else {
					if (man != null) {
						definePackage(pkgname, man, pd.getCodeSource().getLocation());
					} else {
						definePackage(pkgname, null, null, null, null, null, null, null);
					}
				}
			}
			
            return defineClass(name, bytes, pd);
        }
        VERBOSE(name + " not found");
        throw new ClassNotFoundException(name);
        
    }
    
    private boolean isSealed(String name, Manifest man) {
		String path = name.concat("/");
		Attributes attr = man.getAttributes(path);
		String sealed = null;
		if (attr != null) {
			sealed = attr.getValue(Name.SEALED);
		}
		if (sealed == null) {
			if ((attr = man.getMainAttributes()) != null) {
				sealed = attr.getValue(Name.SEALED);
			}
		}
		return "true".equalsIgnoreCase(sealed);
	}

    /**
	 * Defines a new package by name in this ClassLoader. The attributes
	 * contained in the specified Manifest will be used to obtain package
	 * version and sealing information. For sealed packages, the additional URL
	 * specifies the code source URL from which the package was loaded.
	 * 
	 * @param name
	 *            the package name
	 * @param man
	 *            the Manifest containing package version and sealing
	 *            information
	 * @param url
	 *            the code source url for the package, or null if none
	 * @exception IllegalArgumentException
	 *                if the package name duplicates an existing package either
	 *                in this class loader or one of its ancestors
	 * @return the newly defined Package object
	 */
	protected Package definePackage(String name, Manifest man, URL url) throws IllegalArgumentException {
		String path = name.concat("/");
		String specTitle = null, specVersion = null, specVendor = null;
		String implTitle = null, implVersion = null, implVendor = null;
		String sealed = null;
		URL sealBase = null;

		Attributes attr = man.getAttributes(path);
		if (attr != null) {
			specTitle = attr.getValue(Name.SPECIFICATION_TITLE);
			specVersion = attr.getValue(Name.SPECIFICATION_VERSION);
			specVendor = attr.getValue(Name.SPECIFICATION_VENDOR);
			implTitle = attr.getValue(Name.IMPLEMENTATION_TITLE);
			implVersion = attr.getValue(Name.IMPLEMENTATION_VERSION);
			implVendor = attr.getValue(Name.IMPLEMENTATION_VENDOR);
			sealed = attr.getValue(Name.SEALED);
		}
		attr = man.getMainAttributes();
		if (attr != null) {
			if (specTitle == null) {
				specTitle = attr.getValue(Name.SPECIFICATION_TITLE);
			}
			if (specVersion == null) {
				specVersion = attr.getValue(Name.SPECIFICATION_VERSION);
			}
			if (specVendor == null) {
				specVendor = attr.getValue(Name.SPECIFICATION_VENDOR);
			}
			if (implTitle == null) {
				implTitle = attr.getValue(Name.IMPLEMENTATION_TITLE);
			}
			if (implVersion == null) {
				implVersion = attr.getValue(Name.IMPLEMENTATION_VERSION);
			}
			if (implVendor == null) {
				implVendor = attr.getValue(Name.IMPLEMENTATION_VENDOR);
			}
			if (sealed == null) {
				sealed = attr.getValue(Name.SEALED);
			}
		}
        if (sealed != null) {
        	boolean isSealed = Boolean.parseBoolean(sealed);
        	if (isSealed) {
        		sealBase = url;
            }
        }
		return definePackage(name, specTitle, specVersion, specVendor, implTitle, implVersion, implVendor, sealBase);
	}
	
    protected Class defineClass(String name, byte[] bytes, ProtectionDomain pd) throws ClassFormatError {
        // Simple, non wrapped class definition.
    	VERBOSE("defineClass("+name+")");
        return defineClass(name, bytes, 0, bytes.length, pd);
    }
    
    protected void record(ByteCode bytecode) {
        String fileName = bytecode.original;
        // Write out into the record directory.
        File dir = new File(recording, flatten? "": bytecode.codebase);
        File file = new File(dir, fileName);
        if (!file.exists()) {
            file.getParentFile().mkdirs();
            VERBOSE("" + file);
            try {
                FileOutputStream fos = new FileOutputStream(file);
                fos.write(bytecode.bytes);
                fos.close();
                
            } catch (IOException iox) {
                System.err.println(PREFIX() + "unable to record " + file + ": " + iox);
            }
            
        }
    }
    
    /**
     * Make a path canonical, removing . and ..
     */
    protected String canon(String path) {
    	path = path.replaceAll("/\\./", "/");
		String canon = path;
		String next = canon;
		do {
			next = canon;
			canon = canon.replaceFirst("([^/]*/\\.\\./)", "");
		} while (!next.equals(canon));
		return canon;
    }
    /**
     * Overriden to return resources from the appropriate codebase.
     * There are basically two ways this method will be called: most commonly
     * it will be called through the class of an object which wishes to 
     * load a resource, i.e. this.getClass().getResourceAsStream().  Before
     * passing the call to us, java.lang.Class mangles the name.  It 
     * converts a file path such as foo/bar/Class.class into a name like foo.bar.Class, 
     * and it strips leading '/' characters e.g. converting '/foo' to 'foo'.
     * All of which is a nuisance, since we wish to do a lookup on the original
     * name of the resource as present in the One-Jar jar files.  
     * The other way is more direct, i.e. this.getClass().getClassLoader().getResourceAsStream().
     * Then we get the name unmangled, and can deal with it directly. 
     *
     * The problem is this: if one resource is called /foo/bar/data, and another 
     * resource is called /foo.bar.data, both will have the same mangled name, 
     * namely 'foo.bar.data' and only one of them will be visible.  Perhaps the
     * best way to deal with this is to store the lookup names in mangled form, and
     * simply issue warnings if collisions occur.  This is not very satisfactory,
     * but is consistent with the somewhat limiting design of the resource name mapping
     * strategy in Java today.
     */
    public InputStream getByteStream(String resource) {
        
        VERBOSE("getByteStream(" + resource + ")");

        InputStream result = null;
        if (externalClassLoader != null) {
            result = externalClassLoader.getResourceAsStream(resource);
        }

        if (result == null) {
            // Delegate to parent classloader first.
            ClassLoader parent = getParent();
            if (parent != null) {
                result = parent.getResourceAsStream(resource);
            }
        }
        
    	if (result == null) {
        	// Make resource canonical (remove ., .., etc).
        	resource = canon(resource);
            
            // Look up resolving first.  This allows jar-local 
            // resolution to take place.
            ByteCode bytecode = (ByteCode)byteCode.get(resolve(resource));
            if (bytecode == null) {
                // Try again with an unresolved name.
                bytecode = (ByteCode)byteCode.get(resource);
            }
            if (bytecode != null) result = new ByteArrayInputStream(bytecode.bytes);
    	}
    	
        // Contributed by SourceForge "ffrog_8" (with thanks, Pierce. T. Wetter III).
        // Handles JPA loading from jars.
        if (result == null) {
	        if (jarNames.contains(resource)) {
		        // resource wanted is an actual jar
	        	INFO("loading resource file directly" + resource);
		        result = super.getResourceAsStream(resource);
	        }
        }

        // Special case: if we are a wrapping classloader, look up to our
        // parent codebase.  Logic is that the boot JarLoader will have 
        // delegateToParent = false, the wrapping classloader will have 
        // delegateToParent = true;
        if (result == null && delegateToParent) {
            // http://code.google.com/p/onejar-maven-plugin/issues/detail?id=16
			ClassLoader parentClassLoader = getParent();

			// JarClassLoader cannot satisfy requests for actual jar files themselves so it must delegate to it's
			// parent. However, the "parent" is not always a JarClassLoader.
			if (parentClassLoader instanceof JarClassLoader) {
				result = ((JarClassLoader)parentClassLoader).getByteStream(resource);
			} else {
				result = parentClassLoader.getResourceAsStream(resource);
			}
        }
        VERBOSE("getByteStream(" + resource + ") -> " + result);
        return result;
    }
    
    /**
     * Resolve a resource name.  Look first in jar-relative, then in global scope.
     * @param resource
     * @return
     */
    protected String resolve(String $resource) {
        
        if ($resource.startsWith("/")) $resource = $resource.substring(1);
        
        String resource = null;
        String caller = getCaller();
        ByteCode callerCode = (ByteCode)byteCode.get(caller);
        
        if (callerCode != null) {
            // Jar-local first, then global.
            String tmp = callerCode.codebase + "/" + $resource;
            if (byteCode.get(tmp) != null) {
                resource = tmp; 
            } 
        }
        if (resource == null) {
            // One last try.
            if (byteCode.get($resource) == null) {
                resource = null; 
            } else {
                resource = $resource;
            }
        }
        VERBOSE("resource " + $resource + " resolved to " + resource + (callerCode != null? " in codebase " + callerCode.codebase: " (unknown codebase)"));
        return resource;
    }
    
    protected boolean alreadyCached(String name, String jar, ByteArrayOutputStream baos) {
        // TODO: check resource map to see how we will map requests for this
        // resource from this jar file.  Only a conflict if we are using a
        // global map and the resource is defined by more than
        // one jar file (default is to map to local jar).
        ByteCode existing = (ByteCode)byteCode.get(name);
        if (existing != null) {
            byte[] bytes = baos.toByteArray();
            // If bytecodes are identical, no real problem.  Likewise if it's in
            // META-INF.
            if (!Arrays.equals(existing.bytes, bytes) && !name.startsWith("META-INF")) {
                // TODO: this really needs to be a warning, but there needs to be a way
                // to shut it down.  INFO it for now.  Ideally we need to provide a 
                // logging layer (like commons-logging) to allow logging to be delegated.
                String message = existing.name + " in " + jar + " is hidden by " + existing.codebase + " (with different bytecode)";
                if (name.endsWith(".class")) {
                    // This is probably trouble.
                    WARNING(existing.name + " in " + jar + " is hidden by " + existing.codebase + " (with different bytecode)");
                } else {
                    INFO(existing.name + " in " + jar + " is hidden by " + existing.codebase + " (with different bytes)");
                }
            } else {
                VERBOSE(existing.name + " in " + jar + " is hidden by " + existing.codebase + " (with same bytecode)");
            }
            // Speedup GC.
            bytes = null;
            return true;
        }
        return false;
    }
    
    
    protected String getCaller() {
        
        // TODO: revisit caller determination.
        /*
        StackTraceElement[] stack = new Throwable().getStackTrace();
        // Search upward until we get to a known class, i.e. one with a non-null
        // codebase.  Skip anything in the com.simontuffs.onejar package to avoid
        // classloader classes.
        for (int i=0; i<stack.length; i++) {
            String cls = stack[i].getClassName().replace(".","/") + ".class";
            INFO("getCaller(): cls=" + cls);
            if (byteCode.get(cls) != null) {
                String caller = stack[i].getClassName();
                if (!caller.startsWith("com.simontuffs.onejar")) {
                    return cls;
                }
            }
        }
        */
        return null;
    }
    
    /**
     * Sets the name of the used  classes recording directory.
     * 
     * @param $recording A value of "" will use the current working directory 
     * (not recommended).  A value of 'null' will use the default directory, which
     * is called 'recording' under the launch directory (recommended).
     */
    public void setRecording(String $recording) {
        recording = $recording;
        if (recording == null) recording = RECORDING;
    }
    
    public String getRecording() {
        return recording;
    }
    
    public void setRecord(boolean $record) {
        record = $record;
    }
    public boolean getRecord() {
        return record;
    }
    
    public void setFlatten(boolean $flatten) {
        flatten = $flatten;
    }
    public boolean isFlatten() {
        return flatten;
    }
    
    public void setVerbose(boolean $verbose) {
        verbose = $verbose;
        if (verbose) info = true;
    }
    
    public boolean getVerbose() {
        return verbose;
    }
    
    public void setInfo(boolean $info) {
        info = $info;
    }
    public boolean getInfo() {
        return info;
    }
    
    public void setWarning(boolean $warning) {
        warning = $warning;
    }
    public boolean getWarning() {
        return warning;
    }
    protected URLStreamHandler oneJarHandler = new Handler();
    
    // Injectable URL factory.
    public static interface IURLFactory {
        public URL getURL(String codebase, String resource) throws MalformedURLException;
        public URL getCodeBase(String jar) throws MalformedURLException;
    }
    
    // Resolve URL from codebase and resource.  Allow URL factory to be specified by 
    // user of JarClassLoader.
    
    /**
     * FileURLFactory generates URL's which are resolved relative to the filesystem. 
     * These are compatible with frameworks like Spring, but require knowledge of the 
     * location of the one-jar file via Boot.getMyJarPath().
     */
    public static class FileURLFactory implements IURLFactory {
        public URLStreamHandler jarHandler = new URLStreamHandler() {
            protected URLConnection openConnection(URL url) throws IOException {
                URLConnection connection = new OneJarURLConnection(url);
                connection.connect();
                return connection;
            }
        };
        // TODO: Unify getURL and getCodeBase, if possible.
        public URL getURL(String codebase, String resource) throws MalformedURLException {
            if (!codebase.equals("/")) {
                codebase = codebase + "!/";
            } else {
                codebase = "";
            }
            String path = "file:/" + Boot.getMyJarPath() + "!/" + codebase + resource;
            URL url = new URL("jar", "", -1, path, jarHandler);
            return url;
        }
        public URL getCodeBase(String jar) throws MalformedURLException {
            ProtectionDomain cd = JarClassLoader.class.getProtectionDomain();
            URL url = cd.getCodeSource().getLocation();
            if (url != null) {
                url = new URL("jar", "", -1, url + "!/" + jar, jarHandler);
            }
            return url;
        }
    }
    
    /**
     * OneJarURLFactory generates URL's which are efficient, using the in-memory bytecode 
     * to access the resources.
     * @author simon
     *
     */
    public static class OneJarURLFactory implements IURLFactory {
        public URL getURL(String codebase, String resource) throws MalformedURLException {
            String base = resource.endsWith(".class")? "": codebase + "/";
            URL url =  new URL(Handler.PROTOCOL + ":/" + base + resource);
            return url;
        }    
        public URL getCodeBase(String jar) throws MalformedURLException {
            return new URL(Handler.PROTOCOL + ":" + jar);
        }
    }
    
    public URL getResource(String name) {
        // Delegate to external first.
        if (externalClassLoader != null) {
            URL url = externalClassLoader.getResource(name);
            if (url != null)
                return url;
        }
        return super.getResource(name);
    }
    
    protected IURLFactory urlFactory = new FileURLFactory();
    
    // Allow override for urlFactory
    public void setURLFactory(String urlFactory) throws ClassNotFoundException, IllegalAccessException, InstantiationException {
        this.urlFactory = (IURLFactory)loadClass(urlFactory).newInstance();
    }
    
    public IURLFactory getURLFactory() {
        return urlFactory;
    }
    
    /* (non-Javadoc)
     * @see java.lang.ClassLoader#findResource(java.lang.String)
     */
    // TODO: Revisit the issue of protocol handlers for findResource()
    // and findResources();
    protected URL findResource(String $resource) {
        try {
            VERBOSE("findResource(\"" + $resource + "\")");
            URL url = externalClassLoader!=null ? externalClassLoader.getResource($resource) : null;
            if (url != null)
            {
                INFO("findResource() found in external: \"" + $resource + "\"");
                //VERBOSE("findResource(): " + $resource + "=" + url);
                return url;
            }
            // Delegate to parent.
            ClassLoader parent = getParent();
            if (parent != null) {
    	        url = parent.getResource($resource);
    	        if (url != null) {
    	        	return url;
    	        }
            }
            // Do we have the named resource in our cache?  If so, construct a 
            // 'onejar:' URL so that a later attempt to access the resource
            // will be redirected to our Handler class, and thence to this class.
            String resource = resolve($resource);
            if (resource != null) {
                // We know how to handle it.
                ByteCode entry = ((ByteCode) byteCode.get(resource));
                INFO("findResource() found: \"" + $resource + "\" for caller " + getCaller() + " in codebase " + entry.codebase);                
                return urlFactory.getURL(entry.codebase, $resource);
            }
            INFO("findResource(): unable to locate \"" + $resource + "\"");
            // If all else fails, return null.
            return null;
        } catch (MalformedURLException mux) {
            WARNING("unable to locate " + $resource + " due to " + mux);
        }
        return null;
        
    }
    
    protected Enumeration findResources(String name) throws IOException {
        INFO("findResources(" + name + ")");
        INFO("findResources: looking in " + jarNames);
        Iterator iter = jarNames.iterator();
        final List resources = new ArrayList();
        while (iter.hasNext()) {
            String resource = iter.next().toString() + "/" + name;
            ByteCode entry = ((ByteCode) byteCode.get(resource));
            if (byteCode.containsKey(resource)) {
                URL url = urlFactory.getURL(entry.codebase, name);
                INFO("findResources(): Adding " + url + " to resources list.");
                resources.add(url);
            }
        }
        final Iterator ri = resources.iterator();
        return new Enumeration() {
            public boolean hasMoreElements() {
                return ri.hasNext();
            }
            public Object nextElement() {
                return ri.next();
            }
        };
    }
    
    /**
     * Utility to assist with copying InputStream to OutputStream.  All
     * bytes are copied, but both streams are left open.
     * @param in Source of bytes to copy.
     * @param out Destination of bytes to copy.
     * @throws IOException
     */
    protected void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[1024];
        while (true) {
            int len = in.read(buf);
            if (len < 0) break;
            out.write(buf, 0, len);
        }
    }
    
    public String toString() {
        return super.toString() + (name != null? "(" + name + ")": "");
    }
    
    /**
     * Returns name of the classloader.
     * @return
     */
    public String getName() {
        return name;
    }
    
    /**
     * Sets name of the classloader.  Default is null.
     * @param string
     */
    public void setName(String string) {
        name = string;
    }
    
    public void setExpand(boolean expand) {
        noExpand = !expand;
    }
    
    public boolean isExpanded() {
        return expanded;
    }
         
     /** 
     * Preloader for {@link JarClassLoader#findTheLibrary(String, String)} to allow arch-specific native libraries 
     * 
     * @param name the (system specific) name of the requested library
     * @author Sebastian Just 
     */ 
     protected String findLibrary(String name) { 
	     final String os = System.getProperty("os.name").toLowerCase(); 
	     final String arch = System.getProperty("os.arch").toLowerCase(); 
	     
	     final String BINLIB_LINUX32_PREFIX = BINLIB_PREFIX + "linux32/"; 
	     final String BINLIB_LINUX64_PREFIX = BINLIB_PREFIX + "linux64/"; 
	     final String BINLIB_MACOSX_PREFIX = BINLIB_PREFIX + "macosx/"; 
	     final String BINLIB_WINDOWS32_PREFIX = BINLIB_PREFIX + "windows32/"; 
	     final String BINLIB_WINDOWS64_PREFIX = BINLIB_PREFIX + "windows64/"; 
	     
	     String binlib = null; 
	     
	     // Mac 
	     if (os.startsWith("mac os x")) { 
		     //TODO Nood arch detection on mac 
		     binlib = BINLIB_MACOSX_PREFIX; 
		 // Windows
	     } else if (os.startsWith("windows")) { 
		     if (arch.equals("x86")) { 
		    	 binlib = BINLIB_WINDOWS32_PREFIX; 
		     } else { 
		    	 binlib = BINLIB_WINDOWS64_PREFIX; 
		     } 
		 // So it have to be Linux 
	     } else { 
		     if (arch.equals("i386")) { 
		    	 binlib = BINLIB_LINUX32_PREFIX; 
		     } else { 
		    	 binlib = BINLIB_LINUX64_PREFIX; 
		     } 
	     }//TODO Need some work for solaris
	     
	     VERBOSE("Using arch-specific native library path: " + binlib); 
	     
	     String retValue = findTheLibrary(binlib, name); 
	     if (retValue != null) { 
	    	 VERBOSE("Found in arch-specific directory!"); 
	    	 return retValue; 
	     } else { 
	    	 VERBOSE("Search in standard native directory!"); 
	    	 return findTheLibrary(BINLIB_PREFIX, name); 
	     } 
     } 
    
    /**
     * If the system specific library exists in the JAR, expand it and return the path
     * to the expanded library to the caller. Otherwise return null so the caller
     * searches the java.library.path for the requested library.
     *
     *
     * @author Christopher Ottley
     * @param name the (system specific) name of the requested library
     * @param BINLIB_PREFIX the (system specific) folder to search in
     * @return the full pathname to the requested library, or null
     * @see Runtime#loadLibrary()
     * @since 1.2
     */
    protected String findTheLibrary(String BINLIB_PREFIX, String name) {
        String result = null; // By default, search the java.library.path for it
        
        String resourcePath = BINLIB_PREFIX + System.mapLibraryName(name);
        
        // If it isn't in the map, try to expand to temp and return the full path
        // otherwise, remain null so the java.library.path is searched.
        
        // If it has been expanded already and in the map, return the expanded value
        if (binLibPath.get(resourcePath) != null) {
            result = (String)binLibPath.get(resourcePath);
        } else {
            
            // See if it's a resource in the JAR that can be extracted
            File tempNativeLib = null;
            FileOutputStream os = null;
            try {
                int lastdot = resourcePath.lastIndexOf('.');
                String suffix = null;
                if (lastdot >= 0) {
                    suffix = resourcePath.substring(lastdot);
                }
                InputStream is = this.getClass().getResourceAsStream("/" + resourcePath);
                
                if ( is != null ) {
                    tempNativeLib = File.createTempFile(name + "-", suffix);
                    tempNativeLib.deleteOnExit();
                    os = new FileOutputStream(tempNativeLib);
                    copy(is, os);
                    os.close();
                    VERBOSE("Stored native library " + name + " at " + tempNativeLib);
                    result = tempNativeLib.getPath();
                    binLibPath.put(resourcePath, result);
                } else {
                    // Library is not in the jar
                    // Return null by default to search the java.library.path
                    VERBOSE("No native library at " + resourcePath + 
                    "java.library.path will be searched instead.");
                }
            } catch(Throwable e)  {
                // Couldn't load the library
                // Return null by default to search the java.library.path
                WARNING("Unable to load native library: " + e);
            }
            
        }
        
        return result;
    }

    protected String getConfirmation(File location) throws IOException {
        String answer = "";
        while (answer == null || (!answer.startsWith("n") && !answer.startsWith("y") && !answer.startsWith("q"))) {
            promptForConfirm(location);
            BufferedReader br = new BufferedReader(new InputStreamReader(System.in));
            answer = br.readLine();
            br.close();
        }
        return answer;
    }
    
    protected void promptForConfirm(File location) {
        PRINTLN("Do you want to allow '" + Boot.getMyJarName() + "' to expand files into the file-system at the following location?");
        PRINTLN("  " + location);
        PRINT("Answer y(es) to expand files, n(o) to continue without expanding, or q(uit) to exit: ");
    }
    
}
