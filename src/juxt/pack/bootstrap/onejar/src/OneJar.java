/*
 * Copyright (c) 2004-2010, P. Simon Tuffs (simon@simontuffs.com)
 * All rights reserved.
 *
 * See the full license at http://one-jar.sourceforge.net/one-jar-license.html
 * This license is also included in the distributions of this software
 * under doc/one-jar-license.txt
 */
import java.util.Arrays;

import com.simontuffs.onejar.Boot;




/**
 * One-JAR Jar files are intended to be executed using the following kind of command:
 * <pre>
 *   java -jar <one-jar.jar> [args]
 * </pre>
 * This class allows a One-JAR jar-file to be executed using the alternative command:
 * <pre>
 *   java -cp <one-jar.jar> OneJar [args]
 * </pre>
 * Its main role is in testing the behaviour of OneJar on platforms which mangle the classpath
 * when running with the first kind of command, but it can also be a useful alternative
 * execution mechanism.
 * <p>Note: the same effect can be obtained by using the Boot class, albeit with more
 * typing:
 * <pre>
 *   java -cp <one-jar.jar> com.simontuffs.onejar.Boot [args]
 * @author simon
 *
 */
public class OneJar {

    /**
     * @param args
     */
    public static void main(String[] args) throws Exception {
        System.out.println("OneJar" + Arrays.asList(args).toString().replace('[','(').replace(']',')'));
        new OneJar().run(args);
        
    }
    
    public void run(String[] args) throws Exception {
        Boot.run(args);
    }

}
