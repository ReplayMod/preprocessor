package com.replaymod.gradle.preprocess

import java.io.IOException
import java.net.URL
import java.net.URLClassLoader
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * A class loader which strongly prefers loading its own instance of a class rather than using the one from its parent.
 * This allows us to re-order the class path such that more recent versions of libraries can be used even when the old
 * one has already been loaded into the system class loader before we get to run.
 * The only exception are JRE internal classes and certain shared classes as defined by [exclusions].
 */
// Based on https://github.com/EssentialGG/EssentialLoader/blob/fd7e1a427f94f7749783a3665414d41b5974d77a/stage2/launchwrapper/src/main/java/gg/essential/loader/stage2/relaunch/IsolatedClassLoader.java
// Work around for https://github.com/gradle/gradle/issues/34442
internal class IsolatedClassLoader(
    urls: Array<URL>,
    parent: ClassLoader,
    exclusions: List<String> = emptyList(),
) : URLClassLoader(urls, EmptyClassLoader()) {

    private val exclusions: List<String> = listOf(
        "java.",  // JRE cannot be loaded twice
        "javax.",  // JRE cannot be loaded twice
        "sun.",  // JRE internals cannot be loaded twice
        "jdk.",  // JRE internals cannot be loaded twice
    ) + exclusions

    private val classes: MutableMap<String, Class<*>> = ConcurrentHashMap<String, Class<*>>()

    /**
     * The conceptual (but not actual) parent of this class loader.
     *
     * It is not the actual parent because there is no way in Java 8 to re-define packages if they are already
     * defined in your parent. To work around that, our actual parent is an empty class loader, which has no packages
     * loaded at all, and we manually delegate to the conceptual parent as required.
     */
    private val delegateParent: ClassLoader = parent

    @Throws(ClassNotFoundException::class)
    override fun loadClass(name: String): Class<*> {
        // Fast path
        classes[name]?.let { return it }

        // For excluded classes, use the parent class loader
        for (exclusion in exclusions) {
            if (name.startsWith(exclusion)) {
                val cls = delegateParent.loadClass(name)
                classes.put(name, cls)
                return cls
            }
        }

        // Class is not excluded, so we define it in this loader regardless of whether it's already loaded in
        // the parent (cause that's the point of re-launching).
        synchronized(getClassLoadingLock(name)) {
            // Check if we have previously loaded this class. May be the case because we do not synchronize on
            // the lock for the fast path, so it may initiate loading multiple times.
            val cls = findLoadedClass(name)
                // If the have not yet defined the class, let's do that
                ?: super.findClass(name)

            // Class loaded successfully, store it in our map so we can take the fast path in the future
            classes.put(name, cls)
            return cls
        }
    }

    // We redirect this method to our loadClass (which checks the parent for exclusions) because our loadClass is not
    // getting called on OpenJ9 [1] when resolving references [2] from dynamically generated reflection accessor
    // classes [3].
    //
    // [1]: https://github.com/ibmruntimes/openj9-openjdk-jdk8/blob/a1a7ea06e2244735697b8b9ae379de0d85ef4d47/jdk/src/share/classes/sun/reflect/package.html#L116-L132
    // [2]: https://github.com/eclipse-openj9/openj9/blob/b430644c83c2a19a2ecf60fa2eebb03e6976ce42/jcl/src/java.base/share/classes/java/lang/ClassLoader.java#L1347
    // [3]: https://github.com/ibmruntimes/openj9-openjdk-jdk8/blob/c74851c6f9218e365e3e74c5a01ebf794c3721d1/jdk/src/share/classes/sun/reflect/ClassDefiner.java#L70
    @Throws(ClassNotFoundException::class)
    override fun findClass(name: String): Class<*>? {
        return loadClass(name)
    }

    override fun getResource(name: String): URL? {
        // Try our classpath first because the order of our entries may be different from our parent.
        val url = findResource(name)
        if (url != null) {
            return url
        }

        return delegateParent.getResource(name)
    }

    @Throws(IOException::class)
    override fun getResources(name: String): Enumeration<URL> {
        val first = super.getResources(name)
        val second = delegateParent.getResources(name)
        return object : Enumeration<URL> {
            override fun hasMoreElements(): Boolean = first.hasMoreElements() || second.hasMoreElements()
            override fun nextElement(): URL = (if (first.hasMoreElements()) first else second).nextElement()
        }
    }

    /**
     * We use an empty class loader as the actual parent because using null will use the system class loader and there
     * is plenty of stuff in there.
     */
    private class EmptyClassLoader : ClassLoader() {
        override fun getPackage(name: String?): Package? {
            return null
        }

        override fun getPackages(): Array<Package?>? {
            return null
        }

        @Throws(ClassNotFoundException::class)
        override fun loadClass(name: String?, resolve: Boolean): Class<*>? {
            throw ClassNotFoundException()
        }

        override fun getResource(name: String?): URL? {
            return null
        }

        override fun getResources(name: String?): Enumeration<URL?> {
            return Collections.emptyEnumeration<URL?>()
        }
    }

    companion object {
        init {
            registerAsParallelCapable()
        }
    }
}
