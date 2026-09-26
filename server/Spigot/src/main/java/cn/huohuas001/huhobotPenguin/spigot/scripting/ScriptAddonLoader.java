package cn.huohuas001.huhobotPenguin.spigot.scripting;

import cn.huohuas001.huhobotPenguin.spigot.HuHoBotSpigot;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.SourceSection;
import org.graalvm.polyglot.Value;
import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.lib.jse.CoerceJavaToLua;
import org.luaj.vm2.lib.jse.JsePlatform;

import javax.script.Bindings;
import javax.script.ScriptContext;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import javax.script.ScriptException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads {@code .js} / {@code .py} / {@code .lua} addon scripts from
 * {@code plugins/HuHoBotPenguin/addons}.
 *
 * JavaScript uses GraalJS through JSR-223 and Lua uses LuaJ, both wired the
 * way BirdLibraryApi does (https://github.com/prach1121/birdlibraryapi, Apache-2.0).
 * Python uses GraalPy, but that engine is NOT inside the main jar: it is loaded
 * from {@code plugins/HuHoBotPenguin/engines/*.jar} (built by {@code :addon-GraalPy}).
 * Without that jar, {@code .py} scripts fail to load and the plugin keeps running.
 * Each script is also registered as a HuHoBot addon.
 */
public class ScriptAddonLoader {

    private final HuHoBotSpigot plugin;
    private final File scriptsFolder;
    private final File enginesFolder;
    private final Map<String, LoadedScript> loaded = new LinkedHashMap<>();
    private final ScriptEngineManager engineManager = new ScriptEngineManager(ScriptAddonLoader.class.getClassLoader());

    /** GraalPy lives in its own jar. Null until a .py script is loaded, and stays null when the jar is absent. */
    private volatile Engine pythonEngine;
    private volatile String pythonEngineError;

    public ScriptAddonLoader(HuHoBotSpigot plugin) {
        this.plugin = plugin;
        this.scriptsFolder = new File(plugin.getDataFolder(), "addons");
        if (!scriptsFolder.exists()) {
            scriptsFolder.mkdirs();
        }
        this.enginesFolder = new File(plugin.getDataFolder(), "engines");
        if (!enginesFolder.exists()) {
            enginesFolder.mkdirs();
        }
    }

    /**
     * Builds the GraalPy engine from every jar in {@code engines/}. The classloader is
     * parented on this plugin's loader, so the engine jar does not need to bundle a
     * second copy of the polyglot classes the main jar already has. The result is cached.
     */
    private Engine pythonEngine() {
        Engine cached = pythonEngine;
        if (cached != null) return cached;
        synchronized (this) {
            if (pythonEngine != null) return pythonEngine;
            File[] jars = enginesFolder.listFiles((dir, name) -> name.toLowerCase().endsWith(".jar"));
            if (jars == null || jars.length == 0) {
                pythonEngineError = "未安装 GraalPy 引擎。请把 HuHoBot-Engine-GraalPy.jar 放到 " + enginesFolder.getPath();
                return null;
            }
            try {
                URL[] urls = new URL[jars.length];
                for (int i = 0; i < jars.length; i++) urls[i] = jars[i].toURI().toURL();
                // GraalVM 24.1 的 Engine 从「当前线程的 context classloader」里发现语言实现，
                // 没有接受 ClassLoader 的 build() 重载。引擎 jar 的加载器以本插件的加载器为父，
                // 这样 GraalPy 复用主 jar 里已有的 polyglot / truffle，不必再打包一份。
                ClassLoader engineLoader = new URLClassLoader(urls, ScriptAddonLoader.class.getClassLoader());
                ClassLoader previous = Thread.currentThread().getContextClassLoader();
                Thread.currentThread().setContextClassLoader(engineLoader);
                try {
                    pythonEngine = Engine.newBuilder("python")
                            .allowExperimentalOptions(true)
                            .option("engine.WarnInterpreterOnly", "false")
                            .build();
                } finally {
                    Thread.currentThread().setContextClassLoader(previous);
                }
                if (!pythonEngine.getLanguages().containsKey("python")) {
                    pythonEngine.close();
                    pythonEngine = null;
                    pythonEngineError = "engines 目录里的 jar 没有提供 GraalPy 语言";
                    return null;
                }
                plugin.getLogger().info("已从 " + enginesFolder.getPath() + " 加载 GraalPy 引擎（" + jars.length + " 个 jar）");
                return pythonEngine;
            } catch (Throwable error) {
                pythonEngineError = "GraalPy 引擎加载失败: " + error.getMessage();
                plugin.getLogger().severe(pythonEngineError);
                return null;
            }
        }
    }

    private static boolean isScriptFile(String name) {
        String lower = name.toLowerCase();
        return lower.endsWith(".js") || lower.endsWith(".lua") || lower.endsWith(".py");
    }

    public List<ScriptLoadResult> loadAll() {
        List<ScriptLoadResult> results = new ArrayList<>();
        try {
            File[] files = scriptsFolder.listFiles((dir, name) -> isScriptFile(name));
            if (files == null || files.length == 0) {
                plugin.getLogger().info("No .js, .lua or .py addons found in " + scriptsFolder.getPath());
                return results;
            }
            Arrays.sort(files, Comparator.comparing(File::getName));
            for (File f : files) {
                results.add(loadScript(f));
            }
        } catch (Throwable t) {
            plugin.getLogger().severe("Fatal error while scanning the addon scripts folder: " + t);
            t.printStackTrace();
        }
        return results;
    }

    public ScriptLoadResult loadScript(File file) {
        String name = file.getName();
        String lower = name.toLowerCase();
        ScriptLoadResult result;
        if (lower.endsWith(".lua")) {
            result = loadLuaScript(file, name);
        } else if (lower.endsWith(".py")) {
            result = loadPythonScript(file, name);
        } else {
            result = loadJsScript(file, name);
        }
        if (result.success()) {
            LoadedScript loadedScript = loaded.get(name);
            if (loadedScript != null) {
                registerAsAddon(loadedScript.api(), name);
            }
        }
        return result;
    }

    /** Registers the script itself as a HuHoBot addon (idempotent). */
    private void registerAsAddon(BirdScriptApi api, String fileName) {
        try {
            String language = fileName.toLowerCase().endsWith(".py") ? "Python"
                    : fileName.toLowerCase().endsWith(".lua") ? "Lua" : "JavaScript";
            api.registerAddon(api.addonName(), "1.0.0", language + " script addon", fileName);
        } catch (Throwable t) {
            plugin.getLogger().warning("[" + fileName + "] Failed to register HuHoBot addon: " + t.getMessage());
        }
    }

    private ScriptLoadResult loadLuaScript(File file, String name) {
        try {
            Globals globals = JsePlatform.standardGlobals();

            BirdScriptApi api = new BirdScriptApi(plugin, name, scriptsFolder);
            globals.set("Bird", CoerceJavaToLua.coerce(api));
            globals.set("Bukkit", CoerceJavaToLua.coerce(org.bukkit.Bukkit.class));
            globals.set("server", CoerceJavaToLua.coerce(plugin.getServer()));
            globals.set("plugin", CoerceJavaToLua.coerce(plugin));

            try (FileInputStream in = new FileInputStream(file)) {
                LuaValue chunk = globals.load(in, name, "t", globals);
                chunk.call();
            }

            loaded.put(name, new LoadedScript(name, file, globals, api));
            plugin.getLogger().info("Loaded script addon: " + name);
            return ScriptLoadResult.ok(name);

        } catch (LuaError e) {
            String msg = firstLine(e.getMessage());
            plugin.getLogger().severe("[" + name + "] Lua error: " + msg);
            return ScriptLoadResult.error(name, msg);
        } catch (Throwable t) {
            String msg = firstLine(String.valueOf(t.getMessage() != null ? t.getMessage() : t.toString()));
            plugin.getLogger().severe("[" + name + "] Failed to load: " + msg);
            t.printStackTrace();
            return ScriptLoadResult.error(name, msg);
        }
    }

    private ScriptLoadResult loadJsScript(File file, String name) {
        try {
            ScriptEngine engine = engineManager.getEngineByName("graal.js");
            if (engine == null) {
                String msg = "GraalJS engine not found - the Spigot jar must shade js-scriptengine and js-language";
                plugin.getLogger().severe("[" + name + "] " + msg);
                return ScriptLoadResult.error(name, msg);
            }

            Bindings bindings = engine.getBindings(ScriptContext.ENGINE_SCOPE);
            bindings.put("polyglot.js.allowAllAccess", true);
            bindings.put("polyglot.js.allowHostAccess", true);
            bindings.put("polyglot.js.allowHostClassLookup", (java.util.function.Predicate<String>) (s -> true));
            bindings.put("polyglot.js.allowIO", true);
            bindings.put("polyglot.js.nashorn-compat", true);

            BirdScriptApi api = new BirdScriptApi(plugin, name, scriptsFolder);
            bindings.put("Bird", api);
            bindings.put("Bukkit", org.bukkit.Bukkit.class);
            bindings.put("server", plugin.getServer());
            bindings.put("plugin", plugin);

            try (FileReader reader = new FileReader(file, StandardCharsets.UTF_8)) {
                engine.eval(reader);
            }

            loaded.put(name, new LoadedScript(name, file, engine, api));
            plugin.getLogger().info("Loaded script addon: " + name);
            return ScriptLoadResult.ok(name);

        } catch (ScriptException e) {
            String msg = firstLine(e.getMessage());
            plugin.getLogger().severe("[" + name + "] Syntax/Runtime error: " + msg
                    + " (line " + e.getLineNumber() + ", col " + e.getColumnNumber() + ")");
            return ScriptLoadResult.error(name, msg, e.getLineNumber(), e.getColumnNumber());
        } catch (Throwable t) {
            String msg = firstLine(String.valueOf(t.getMessage() != null ? t.getMessage() : t.toString()));
            plugin.getLogger().severe("[" + name + "] Failed to load: " + msg);
            t.printStackTrace();
            return ScriptLoadResult.error(name, msg);
        }
    }

    private ScriptLoadResult loadPythonScript(File file, String name) {
        Engine engine = pythonEngine();
        if (engine == null) {
            plugin.getLogger().severe("[" + name + "] " + pythonEngineError);
            return ScriptLoadResult.error(name, pythonEngineError);
        }
        Context context = null;
        try {
            context = Context.newBuilder("python")
                    .engine(engine)
                    .allowAllAccess(true)
                    .option("engine.WarnInterpreterOnly", "false")
                    .build();

            BirdScriptApi api = new BirdScriptApi(plugin, name, scriptsFolder);
            Value bindings = context.getBindings("python");
            bindings.putMember("Bird", api);
            bindings.putMember("Bukkit", org.bukkit.Bukkit.class);
            bindings.putMember("server", plugin.getServer());
            bindings.putMember("plugin", plugin);

            context.eval(Source.newBuilder("python", file).build());
            loaded.put(name, new LoadedScript(name, file, context, api));
            plugin.getLogger().info("Loaded script addon: " + name);
            return ScriptLoadResult.ok(name);
        } catch (PolyglotException e) {
            closeContext(context);
            String msg = firstLine(e.getMessage());
            int line = -1;
            SourceSection section = e.getSourceLocation();
            if (section != null && section.isAvailable()) line = section.getStartLine();
            plugin.getLogger().severe("[" + name + "] Python error: " + msg
                    + (line >= 0 ? " (line " + line + ")" : ""));
            return line >= 0 ? ScriptLoadResult.error(name, msg, line, -1) : ScriptLoadResult.error(name, msg);
        } catch (Throwable t) {
            closeContext(context);
            String msg = firstLine(String.valueOf(t.getMessage() != null ? t.getMessage() : t.toString()));
            plugin.getLogger().severe("[" + name + "] Failed to load: " + msg);
            t.printStackTrace();
            return ScriptLoadResult.error(name, msg);
        }
    }

    private void closeEngine(Object engine) {
        if (engine instanceof Context context) closeContext(context);
    }

    private void closeContext(Context context) {
        if (context == null) return;
        try {
            context.close(true);
        } catch (Exception ignored) {
        }
    }

    private String firstLine(String message) {
        if (message == null) return "unknown error";
        String[] parts = message.split("\n");
        return parts[0].trim();
    }

    public void unloadAll() {
        for (LoadedScript s : loaded.values()) {
            try {
                s.api().unregisterAll();
            } catch (Exception e) {
                plugin.getLogger().warning("Problem unloading script " + s.name() + ": " + e.getMessage());
            }
            closeEngine(s.engine());
        }
        loaded.clear();
    }

    public List<ScriptLoadResult> reloadAll() {
        unloadAll();
        return loadAll();
    }

    public ScriptLoadResult reloadOne(String fileNameInput) {
        String lowerInput = fileNameInput.toLowerCase();
        String fileName;
        if (lowerInput.endsWith(".js") || lowerInput.endsWith(".lua") || lowerInput.endsWith(".py")) {
            fileName = fileNameInput;
        } else {
            File luaCandidate = new File(scriptsFolder, fileNameInput + ".lua");
            File pyCandidate = new File(scriptsFolder, fileNameInput + ".py");
            if (luaCandidate.isFile()) {
                fileName = fileNameInput + ".lua";
            } else if (pyCandidate.isFile()) {
                fileName = fileNameInput + ".py";
            } else {
                fileName = fileNameInput + ".js";
            }
        }

        File file = new File(scriptsFolder, fileName);
        if (!file.exists() || !file.isFile()) {
            return ScriptLoadResult.notFound(fileName);
        }

        LoadedScript existing = loaded.remove(fileName);
        if (existing != null) {
            try {
                existing.api().unregisterAll();
            } catch (Exception e) {
                plugin.getLogger().warning("Problem unloading previous version of " + fileName + ": " + e.getMessage());
            }
            closeEngine(existing.engine());
        }

        return loadScript(file);
    }

    public List<String> listScriptFileNames() {
        File[] files = scriptsFolder.listFiles((dir, name) -> isScriptFile(name));
        List<String> names = new ArrayList<>();
        if (files != null) {
            for (File f : files) {
                names.add(f.getName());
            }
        }
        return names;
    }

    public int getLoadedCount() {
        return loaded.size();
    }

    public File getScriptsFolder() {
        return scriptsFolder;
    }
}
