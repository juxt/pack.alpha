= Pack (alpha)
ifdef::env-github[]
:toc:
:toclevels: 4
endif::[]

Package up Clojure projects in various ways, based on a deps.edn file.

Want to have a chat before opening a ticket?
Need help or support from the community?
Find us in the link:https://clojurians.zulipchat.com/#narrow/stream/151045-JUXT[Clojurians Zulipchat #JUXT] stream.

== Usage

=== With tools.build

Pack provides the namespace juxt.pack.api which works well alongside tools.build.
Find reference documentation on link:https://github.com/juxt/pack.alpha/blob/master/src/juxt/pack/api.clj[the vars].

=== As a tool

Pack also works standalone as a tool you can invoke with deps.edn.
Add this alias to your project:

[source,clojure]
----
;; add this to :aliases:
:pack {:deps {io.github.juxt/pack.alpha {:git/sha "843475881c5b3ba1a2c9f102f6f5f86f3293a574"}}
       :ns-default juxt.pack.cli.api}
----

Then you can invoke it with `clj -T:pack`.
To see usage information, use `clojure -A:deps -T:pack help/dir`

== Features

Pack provides fast, conflict-less, ways to package Clojure programs.
It provides:

* Docker images
* AWS Lambda deployment package
* Self-executable jars (Using One-JAR)
* "Skinny" jars (Jars which do not contain their dependencies)
* Library jars

=== What does conflict-less mean?

Pack will always package your application so you don't need to worry about multiple copies of the same file on the classpath.
Pack preserves the classpath for all of it's output formats.

Uberjars require that files on the classpath are uniquely named.
To do this, they have to resolve conflicts between files with the same name.
For example, if you two dependencies com.example/A and com.example/B, and both contain the file "data_readers.clj" in them, what do you do?
Often configuration is needed to get this right, as many libraries aren't supported out of the box.
Some tools will even ignore copyright restrictions that require you to keep them in your final jar as a convenience.

== Examples

PR your project here!

== Guides

PR your guide here!
