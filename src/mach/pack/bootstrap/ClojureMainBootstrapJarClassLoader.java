import com.jdotsoft.jarloader.JarClassLoader;

public class ClojureMainBootstrapJarClassLoader {
  public static void main(String[] args) {
    JarClassLoader jcl = new JarClassLoader();
    try {
      jcl.invokeMain("clojure.main", args);
    } catch (Throwable e) {
      e.printStackTrace();
    }
  }
}
