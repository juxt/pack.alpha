/*
 * Copyright (c) 2004-2010, P. Simon Tuffs (simon@simontuffs.com)
 * All rights reserved.
 *
 * See the full license at http://one-jar.sourceforge.net/one-jar-license.html
 * This license is also included in the distributions of this software
 * under doc/one-jar-license.txt
 */	 

package com.simontuffs.onejar;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URL;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.Properties;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarInputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Run a java application which requires multiple support jars from inside
 * a single jar file.
 * 
 * <p>
 * Developer time JVM properties:
 * <pre>
 *   -Done-jar.main.class={name}  Use named class as main class to run. 
 *   -Done-jar.record[=recording] Record loaded classes into "recording" directory.
 *                                Flatten jar.names into directory tree suitable 
 * 								  for use as a classpath.
 *   -Done-jar.jar.names          Record loaded classes, preserve jar structure
 *   -Done-jar.verbose            Run the JarClassLoader in verbose mode.
 * </pre>
 * @author simon@simontuffs.com (<a href="http://www.simontuffs.com">http://www.simontuffs.com</a>)
 */
public class Boot {
	
    /**
	 * The name of the manifest attribute which controls which class 
	 * to bootstrap from the jar file.  The boot class can
	 * be in any of the contained jar files.
	 */
	public final static String BOOT_CLASS = "Boot-Class";
    public final static String ONE_JAR_CLASSLOADER = "One-Jar-Class-Loader";
    public final static String ONE_JAR_MAIN_CLASS = "One-Jar-Main-Class";
    public final static String ONE_JAR_DEFAULT_MAIN_JAR = "One-Jar-Default-Main-Jar";
    public final static String ONE_JAR_MAIN_ARGS = "One-Jar-Main-Args";
    public final static String ONE_JAR_URL_FACTORY = "One-Jar-URL-Factory";
	
	public final static String MANIFEST = "META-INF/MANIFEST.MF";
	public final static String MAIN_JAR = "main/main.jar";

	public final static String WRAP_CLASS_LOADER = "Wrap-Class-Loader";
    public final static String WRAP_DIR = "wrap";
	public final static String WRAP_JAR = "/" + WRAP_DIR + "/wraploader.jar";

    // System properties.
	public final static String PROPERTY_PREFIX = "one-jar.";
	public final static String P_MAIN_CLASS = PROPERTY_PREFIX + "main.class";
	public final static String P_MAIN_JAR = PROPERTY_PREFIX + "main.jar";
    public final static String P_MAIN_APP = PROPERTY_PREFIX + "main.app";
	public final static String P_RECORD = PROPERTY_PREFIX + "record";
	public final static String P_JARNAMES = PROPERTY_PREFIX + "jar.names";
	public final static String P_VERBOSE = PROPERTY_PREFIX + "verbose";
	public final static String P_INFO = PROPERTY_PREFIX + "info";
	public final static String P_WARNING = PROPERTY_PREFIX + "warning";
    public final static String P_STATISTICS = PROPERTY_PREFIX + "statistics";
    public final static String P_SHOW_PROPERTIES = PROPERTY_PREFIX + "show.properties";
    public final static String P_JARPATH = PROPERTY_PREFIX + "jar.path";
    public final static String P_ONE_JAR_CLASS_PATH = PROPERTY_PREFIX + "class.path";
    public final static String P_JAVA_CLASS_PATH = "java.class.path";
    public final static String P_PATH_SEPARATOR = "|";
    public final static String P_EXPAND_DIR = PROPERTY_PREFIX + "expand.dir";
    
    // Command-line arguments
    public final static String A_HELP    = "--one-jar-help";
    public final static String A_VERSION = "--one-jar-version";
    
    public final static String[] HELP_PROPERTIES = {
        P_MAIN_CLASS, "Specifies the name of the class which should be executed \n(via public static void main(String[])", 
        P_MAIN_APP,   "Specifies the name of the main/<app>.jar to be executed", 
        P_RECORD,     "true:  Enables recording of the classes loaded by the application",
        P_JARNAMES,   "true:  Recorded classes are kept in directories corresponding to their jar names.\n" + 
                      "false: Recorded classes are flattened into a single directory.  \nDuplicates are ignored (first wins)",
        P_VERBOSE,    "true:  Print verbose classloading information", 
        P_INFO,       "true:  Print informative classloading information", 
        P_WARNING,    "true:  Print serious classloading warnings", 
        P_STATISTICS, "true:  Shows statistics about the One-Jar Classloader",
        P_JARPATH,    "Full path of the one-jar file being executed.  \nOnly needed if java.class.path does not contain the path to the jar, e.g. on Max OS/X.",
        P_ONE_JAR_CLASS_PATH,    "Extra classpaths to be added to the execution environment.  \nUse platform independent path separator '" + P_PATH_SEPARATOR + "'",
        P_EXPAND_DIR, "Directory to use for expanded files.",
        P_SHOW_PROPERTIES, "true:  Shows the JVM system properties.",
    };
	
    public final static String[] HELP_ARGUMENTS = {
        A_HELP,       "Shows this message, then exits.",
        A_VERSION,    "Shows the version of One-JAR, then exits.", 
    };
    
    protected static String mainJar;
    
	protected static boolean warning = true, info, verbose, statistics;
    protected static String myJarPath;
    
    protected static long startTime = System.currentTimeMillis();
    protected static long endTime = 0;
    

	// Singleton loader.  This must not be changed once it is set, otherwise all
    // sorts of nasty class-cast exceptions will ensue.  Hence we control 
    // access to it strongly.
	private static JarClassLoader loader = null;
	
    
	/**
     * This method provides access to the bootstrap One-JAR classloader which 
     * is needed in the URL connection Handler when opening streams relative
     * to classes.  
     * @return
	 */
    public synchronized static JarClassLoader getClassLoader() {
		return loader;
	}
    
    /**
     * This is the single point of entry for setting the "loader" member.  It checks to 
     * make sure programming errors don't call it more than once.
     * @param $loader
     */
    public synchronized static void setClassLoader(JarClassLoader $loader) {
        if (loader != null) throw new RuntimeException("Attempt to set a second Boot loader");
        loader = $loader;
    }

	protected static void VERBOSE(String message) {
		if (verbose) System.out.println("Boot: " + message);
	}
    
	protected static void WARNING(String message) {
		System.err.println("Boot: Warning: " + message); 
	}
	
	protected static void INFO(String message) {
		if (info) System.out.println("Boot: Info: " + message);
	}
    
    protected static void PRINTLN(String message) {
        System.out.println("Boot: " + message);
    }

    public static void main(String[] args) throws Exception {
    	run(args);
    }
    
    public static void run(String args[]) throws Exception {
		
        args = processArgs(args);
        
    	// Is the main class specified on the command line?  If so, boot it.
    	// Otherwise, read the main class out of the manifest.
		String mainClass = null;
		
		{
			// Default properties are in resource 'one-jar.properties'.
			Properties properties = new Properties();
			String props = "one-jar.properties";
			InputStream is = Boot.class.getResourceAsStream("/" + props); 
			if (is != null) {
				INFO("loading properties from " + props);
				properties.load(is);
			}
				 
			// Merge in anything in a local file with the same name.
			if (new File(props).exists()) {
    			is = new FileInputStream(props);
    			if (is != null) {
    				INFO("merging properties from " + props);
    				properties.load(is);
    			} 
			}
			
			// Set system properties only if not already specified.
			Enumeration _enum = properties.propertyNames();
			while (_enum.hasMoreElements()) {
				String name = (String)_enum.nextElement();
				if (System.getProperty(name) == null) {
					System.setProperty(name, properties.getProperty(name));
				}
			}
		}		
        if (Boolean.valueOf(System.getProperty(P_SHOW_PROPERTIES, "false")).booleanValue()) {
            // What are the system properties.
            Properties props = System.getProperties();
            String keys[] = (String[])props.keySet().toArray(new String[]{});
            Arrays.sort(keys);
            
            for (int i=0; i<keys.length; i++) {
                String key = keys[i];
                System.out.println(key + "=" + props.get(key));
            }
        }

        // Process developer properties:
        if (mainClass == null) {
        	mainClass = System.getProperty(P_MAIN_CLASS);
        }
        
		if (mainJar == null) {
			String app = System.getProperty(P_MAIN_APP);
			if (app != null) {
				mainJar = "main/" + app + ".jar";
			} else {
				mainJar = System.getProperty(P_MAIN_JAR, MAIN_JAR);
			}
        }
		
        // Pick some things out of the top-level JAR file.
        String jar = getMyJarPath();
        JarFile jarFile = new JarFile(jar);
        Manifest manifest = jarFile.getManifest();
        Attributes attributes = manifest.getMainAttributes();
        String bootLoaderName = attributes.getValue(ONE_JAR_CLASSLOADER);
 
        if (mainJar == null) {
            mainJar = attributes.getValue(ONE_JAR_DEFAULT_MAIN_JAR);
        }
        
        String mainargs = attributes.getValue(ONE_JAR_MAIN_ARGS);
        if (mainargs != null && args.length == 0) {
            // Replace the args with built-in.  Support escaped whitespace.
            args = mainargs.split("[^\\\\]\\s");
            for (int i=0; i<args.length; i++) {
                args[i] = args[i].replaceAll("\\\\(\\s)", "$1");
            }
        }
        
		// If no main-class specified, check the manifest of the main jar for
		// a Boot-Class attribute.
		if (mainClass == null) {
            mainClass = attributes.getValue(ONE_JAR_MAIN_CLASS);
            if (mainClass == null) {
                mainClass = attributes.getValue(BOOT_CLASS);
                if (mainClass != null) {
                    WARNING("The manifest attribute " + BOOT_CLASS + " is deprecated in favor of the attribute " + ONE_JAR_MAIN_CLASS);
                }
            } 
		}
        
		if (mainClass == null) {
			// Still don't have one (default).  One final try: look for a jar file in a
			// main directory.  There should be only one, and it's manifest 
			// Main-Class attribute is the main class.  The JarClassLoader will take
			// care of finding it.
			InputStream is = Boot.class.getResourceAsStream("/" + mainJar);
			if (is != null) {
				JarInputStream jis = new JarInputStream(is);
				Manifest mainmanifest = jis.getManifest();
                jis.close();
				mainClass = mainmanifest.getMainAttributes().getValue(Attributes.Name.MAIN_CLASS);
			} else {
			    // There is no main jar. Info unless mainJar is empty string.  
			    // The load(mainClass) will scan for main jars anyway.
				if (!"".equals(mainJar)){ 
                    INFO("Unable to locate main jar '" + mainJar + "' in the JAR file " + getMyJarPath());
				}
			}
		}
	
		// Do we need to create a wrapping classloader?  Check for the
		// presence of a "wrap" directory at the top of the jar file.
		URL url = Boot.class.getResource(WRAP_JAR);
		
		if (url != null) {
			// Wrap class loaders.
            final JarClassLoader bootLoader = getBootLoader(bootLoaderName);
			bootLoader.load(null);
			
			// Read the "Wrap-Class-Loader" property from the wraploader jar file.
			// This is the class to use as a wrapping class-loader.
            InputStream is = Boot.class.getResourceAsStream(WRAP_JAR);
            if (is != null) {
    			JarInputStream jis = new JarInputStream(is);
    			final String wrapLoader = jis.getManifest().getMainAttributes().getValue(WRAP_CLASS_LOADER);
                jis.close();
    			if (wrapLoader == null) {
    				WARNING(url + " did not contain a " + WRAP_CLASS_LOADER + " attribute, unable to load wrapping classloader");
    			} else {
    				INFO("using " + wrapLoader);
                    JarClassLoader wrapped = getWrapLoader(bootLoader, wrapLoader);
                    if (wrapped == null) {
                        WARNING("Unable to instantiate " + wrapLoader + " from " + WRAP_DIR + ": using default JarClassLoader");
                        wrapped = getBootLoader(null);
                    }
                    setClassLoader(wrapped);
    			}
            }
		} else {
            setClassLoader(getBootLoader(bootLoaderName, Boot.class.getClassLoader()));
            INFO("using JarClassLoader: " + getClassLoader().getClass().getName());
		}
        
		// Allow injection of the URL factory.
		String urlfactory = attributes.getValue(ONE_JAR_URL_FACTORY);
		if (urlfactory != null) {
		    loader.setURLFactory(urlfactory);
		}
		   
		mainClass = loader.load(mainClass);
        
        if (mainClass == null && !loader.isExpanded()) 
            throw new Exception(getMyJarName() + " main class was not found (fix: add main/main.jar with a Main-Class manifest attribute, or specify -D" + P_MAIN_CLASS + "=<your.class.name>), or use " + ONE_JAR_MAIN_CLASS + " in the manifest");

        if (mainClass != null) {
        	// Guard against the main.jar pointing back to this
        	// class, and causing an infinite recursion.
            String bootClass = Boot.class.getName();
        	if (bootClass.equals(mainClass))
        		throw new Exception(getMyJarName() + " main class (" + mainClass + ") would cause infinite recursion: check main.jar/META-INF/MANIFEST.MF/Main-Class attribute: " + mainClass);
        	
        	Class cls = loader.loadClass(mainClass);
        	
            endTime = System.currentTimeMillis();
            showTime();
            
        	Method main = cls.getMethod("main", new Class[]{String[].class}); 
        	main.invoke(null, new Object[]{args});
        }
    }

    public static void showTime() {
        long endtime = System.currentTimeMillis();
        if (statistics) {
            PRINTLN("Elapsed time: " + (endtime - startTime) + "ms");
        }
    }
    
    public static void setProperties(IProperties jarloader) {
        INFO("setProperties(" + jarloader + ")");
        if (getProperty(P_RECORD, "false")) {
            jarloader.setRecord(true);
            jarloader.setRecording(System.getProperty(P_RECORD));
        } 
        if (getProperty(P_JARNAMES, "false")) {
            jarloader.setRecord(true);
            jarloader.setFlatten(false);
        }
        // TODO: clean up the use of one-jar.{verbose,info,warning} properties.
        if (verbose = getProperty(P_VERBOSE, "false")) {
            jarloader.setVerbose(true);
            jarloader.setInfo(true);
        } 
        jarloader.setInfo(info=getProperty(P_INFO, "false"));
        jarloader.setWarning(warning=getProperty(P_WARNING, "true"));
        
        statistics = getProperty(P_STATISTICS, "false");
    }
    
    public static boolean getProperty(String key, String $default) {
        return Boolean.valueOf(System.getProperty(key, "false")).booleanValue();
    }
    
    public static String getMyJarName() {
        String name = getMyJarPath();
        int last = name.lastIndexOf("/");
        if (last >= 0) {
            name = name.substring(last+1); 
        }
        return name;
    }
    
    public static String getMyJarPath() {
        if (myJarPath != null) {
            return myJarPath;
        }
        myJarPath = System.getProperty(P_JARPATH); 
        if (myJarPath == null) {
            try {
                // Hack to obtain the name of this jar file.
                String jarname = System.getProperty(P_JAVA_CLASS_PATH);
                // Open each Jar file looking for this class name.  This allows for
                // JVM's that place more than the jar file on the classpath.
                String jars[] =jarname.split(System.getProperty("path.separator"));
                for (int i=0; i<jars.length; i++) {
                    jarname = jars[i];
                    VERBOSE("Checking " + jarname + " as One-Jar file");
                    // Allow for URL based paths, as well as file-based paths.  File
                    InputStream is = null;
                    try {
                        is = new URL(jarname).openStream();
                    } catch (MalformedURLException mux) {
                        // Try a local file.
                        try {
                            is = new FileInputStream(jarname);
                        } catch (IOException iox) {
                            // Ignore..., but it isn't good to have bad entries on the classpath.
                            continue;
                        }
                    }
                    ZipEntry entry = findJarEntry(new JarInputStream(is), Boot.class.getName().replace('.', '/') + ".class");
                    if (entry != null) {
                        myJarPath = jarname;
                        break;
                    } else {
                        // One more try as a Zip file: supports launch4j on Windows.
                        entry = findZipEntry(new ZipFile(jarname), Boot.class.getName().replace('.', '/') + ".class");
                        if (entry != null) {
                            myJarPath = jarname;
                            break;
                        }
                    }
                }
            } catch (Exception x) {
                x.printStackTrace();
                WARNING("jar=" + myJarPath + " loaded from " + P_JAVA_CLASS_PATH + " (" + System.getProperty(P_JAVA_CLASS_PATH) + ")");
            }
        }
        if (myJarPath == null) {
            throw new IllegalArgumentException("Unable to locate " + Boot.class.getName() + " in the java.class.path: consider using -D" + P_JARPATH + " to specify the one-jar filename.");
        }
        // Normalize those annoying DOS backslashes.
        myJarPath = myJarPath.replace('\\', '/');
        return myJarPath;
    }
    
    public static JarEntry findJarEntry(JarInputStream jis, String name) throws IOException {
        JarEntry entry;
        while ((entry = jis.getNextJarEntry()) != null) {
            if (entry.getName().equals(name)) {
                return entry;
            }
        }
        return null;
    }
    
    public static ZipEntry findZipEntry(ZipFile zip, String name) throws IOException {
        Enumeration entries = zip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = (ZipEntry) entries.nextElement();
            VERBOSE(("findZipEntry(): entry=" + entry.getName()));
            if (entry.getName().equals(name)) 
                return entry;
        }
        return null;
    }
    
    public static int firstWidth(String[] table) {
        int width = 0;
        for (int i=0; i<table.length; i+=2) {
            if (table[i].length() > width) width = table[i].length();
        }
        return width;
    }
    
    public static String pad(String indent, String string, int width) {
        StringBuffer buf = new StringBuffer();
        buf.append(indent);
        buf.append(string);
        for (int i=0; i<width-string.length(); i++) {
            buf.append(" ");
        }
        return buf.toString();
    }
    
    public static String wrap(String indent, String string, int width) {
        String padding = pad(indent, "", width);
        string = string.replaceAll("\n", "\n" + padding);
        return string;
    }

    public static String[] processArgs(String args[]) throws Exception {
        // Check for arguments which matter to us, and strip them.
    	VERBOSE("processArgs(" + Arrays.asList(args) + ")");
    	ArrayList list = new ArrayList();
    	for (int a=0; a<args.length; a++) {
        	String argument = args[a];
	        if (argument.startsWith(A_HELP)) {
	            int width = firstWidth(HELP_ARGUMENTS);
	            // Width of first column
	            
	            System.out.println("One-Jar uses the following command-line arguments");
	            for (int i=0; i<HELP_ARGUMENTS.length; i++) {
	                System.out.print(pad("    ", HELP_ARGUMENTS[i++], width+1));
	                System.out.println(wrap("    ", HELP_ARGUMENTS[i], width+1));
	            }
	            System.out.println();
	            
	            width = firstWidth(HELP_PROPERTIES);
	            System.out.println("One-Jar uses the following VM properties (-D<property>=<true|false|string>)");
	            for (int i=0; i<HELP_PROPERTIES.length; i++) {
	                System.out.print(pad("    ", HELP_PROPERTIES[i++], width+1));
	                System.out.println(wrap("    ", HELP_PROPERTIES[i], width+1));
	            }
	            System.out.println();
	            System.exit(0);
	        } else if (argument.startsWith(A_VERSION)) {
	            InputStream is = Boot.class.getResourceAsStream("/.version");
	            if (is != null) {
	                BufferedReader br = new BufferedReader(new InputStreamReader(is)); 
	                String version = br.readLine();
	                br.close();
	                System.out.println("One-JAR version " + version);
	            } else {
	                System.out.println("Unable to determine One-JAR version (missing /.version resource in One-JAR archive)");
	            }
	            System.exit(0);
	        } else {
	        	list.add(argument);
	        }
        }
    	return (String[])list.toArray(new String[0]);
    }
    
    protected static JarClassLoader getBootLoader(final String loader) {
        JarClassLoader bootLoader = (JarClassLoader)AccessController.doPrivileged(
                new PrivilegedAction() {
                    public Object run() {
                        if (loader != null) {
                            try {
                                Class cls = Class.forName(loader);
                                Constructor ctor = cls.getConstructor(new Class[]{String.class});
                                return ctor.newInstance(new Object[]{WRAP_DIR});
                            } catch (Exception x) {
                                WARNING("Unable to instantiate " + loader + ": " + x + " continuing using default " + JarClassLoader.class.getName());
                            }
                        }
                        return new JarClassLoader(WRAP_DIR);
                    }
                }
            );
        return bootLoader;
    }
    
    protected static JarClassLoader getBootLoader(final String loader, ClassLoader parent) {
        return (JarClassLoader)AccessController.doPrivileged(
            new PrivilegedAction() {
                public Object run() {
                    if (loader != null) {
                        try {
                            Class cls = Class.forName(loader);
                            Constructor ctor = cls.getConstructor(new Class[]{ClassLoader.class});
                            return ctor.newInstance(new Object[]{Boot.class.getClassLoader()});
                        } catch (Exception x) {
                            WARNING("Unable to instantiate " + loader + ": " + x + " continuing using default " + JarClassLoader.class.getName());
                        }
                    }
                    return new JarClassLoader(Boot.class.getClassLoader());
                }
            }
        );        
    }
    
    protected static JarClassLoader getWrapLoader(final ClassLoader bootLoader, final String wrapLoader) {
        return ((JarClassLoader)AccessController.doPrivileged(
            new PrivilegedAction() {
                public Object run() {
                    try {
                        Class jarLoaderClass = bootLoader.loadClass(wrapLoader);
                        Constructor ctor = jarLoaderClass.getConstructor(new Class[]{ClassLoader.class});
                        return ctor.newInstance(new Object[]{bootLoader});
                    } catch (Throwable t) {
                        WARNING(t.toString());
                    }
                    return null;
                }
            }));
    }

    public static long getEndTime() {
        return endTime;
    }

    public static long getStartTime() {
        return startTime;
    }
    
}
