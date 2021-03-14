### The Preprocessor
To support multiple Minecraft versions with the ReplayMod, a [JCP](https://github.com/raydac/java-comment-preprocessor)-inspired preprocessor is used:
```java
        //#if MC>=11200
        // This is the block for MC >= 1.12.0
        category.addDetail(name, callable::call);
        //#else
        //$$ // This is the block for MC < 1.12.0
        //$$ category.setDetail(name, callable::call);
        //#endif
```
Any comments starting with `//$$` will automatically be introduced / removed based on the surrounding condition(s).
Normal comments are left untouched. The `//#else` branch is optional.

Conditions can be nested arbitrarily but their indention shall always be equal to the indention of the code at the `//#if` line.
The `//$$` shall be aligned with the inner-most `//#if`.
```java
    //#if MC>=10904
    public CPacketResourcePackStatus makeStatusPacket(String hash, Action action) {
        //#if MC>=11002
        return new CPacketResourcePackStatus(action);
        //#else
        //$$ return new CPacketResourcePackStatus(hash, action);
        //#endif
    }
    //#else
    //$$ public C19PacketResourcePackStatus makeStatusPacket(String hash, Action action) {
    //$$     return new C19PacketResourcePackStatus(hash, action);
    //$$ }
    //#endif
```
Code for the more recent MC version shall be placed in the first branch of the if-else-construct.
Version-dependent import statements shall be placed separately from and after all other imports but before the `static` and `java.*` imports.

The source code resides in `src/main` (gradle project determined by `versions/mainVersion` e.g. with `11404` it'll be `:1.14.4`) and is automatically passed through the
preprocessor when any of the other versions are built (gradle projects `:1.8`, `:1.8.9`, etc.).
Do **NOT** edit any of the code in `versions/$MCVERSION/build/` as it is automatically generated and will be overwritten without warning.

You can pass the original source code through the preprocessor if you wish to develop/debug with another version of Minecraft:
```bash
./gradle :1.9.4:setCoreVersion # switches all sources in src/main to 1.9.4
```

Make sure to switch back to the most recent branch before committing!
Care should also be taken that switching to a different branch and back doesn't introduce any uncommitted changes (e.g. due to different indention, especially in case of nested conditions).

The `replaymod_at.cfg` file uses the same preprocessor but with different keywords (see already existent examples in that file).
If required, more file extensions and keywords can be added to the implementation.

## Per-version files

If entire files are very version specific, they may be overwritten for any version by placing a new file with the same package and name in `versions/$MCVERSION/src/main/java` (or the respective source set / language folder).
If such a file is present, the overwritten file will no longer be derived from another version and any downstream versions will be derived from the new file instead.
This also has the huge advantage that the file may be edited with full IDE support because it is actually part of the respective version's Gradle project.

This feature is fully compatible with `setCoreVersion` and overwrite files will be moved generated/removed as required such that switching back and forth leaves the same result as you started out with.
The core project itself does not allow for overwrites and any present in its folder will be deleted on `setCoreVersion`.

## Patterns

The preprocessor also supports defining simple "search and replace"-like patterns (but smarter in that they are type-aware) annotated by a `@Pattern` annotation in one or more central places which then are applied all over the code base.
This allows code which would previously have to be written with preprocessor statements or as `MCVer.getWindow(mc)` all over the code base to instead now use the much more intuitive `mc.getWindow()` and be automatically converted to `mc.window` (or even a Window stub object) on remap if a pattern for that exists anywhere in the same source tree:
```java
    @Pattern
    private static Window getWindow(MinecraftClient mc) {
        //#if MC>=11500
        return mc.getWindow();
        //#elseif MC>=11400
        //$$ return mc.window;
        //#else
        //$$ return new com.replaymod.core.versions.Window(mc);
        //#endif
    }
```
All pattern cases should be a single line as to not mess with indentation and/or line count.
Any arguments passed to the pattern must be used in the pattern in the same order in every case (introducing in-line locals to work around that is fine).
Defining and/or applying patterns in/on Kotlin code is not yet supported.

To use this feature, you must create a `Pattern` (name may be different) annotation in your mod:
```java
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.METHOD)
public @interface Pattern {
}
```
and then declare it in your `build.gradle`:
```groovy
preprocess {
    patternAnnotation.set("com.replaymod.gradle.remap.Pattern")
}
```

## License
The Preprocessor is provided under the terms of the GNU General Public License Version 3 or (at your option) any later version.
See `LICENSE.md` for the full license text.