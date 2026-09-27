package cn.huohuas001.huhobot.graalpy;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.SourceSection;
import org.graalvm.polyglot.Value;

import java.io.File;

/**
 * Creates the GraalPy engine from inside the engine jar's own classloader.
 *
 * GraalVM 24.1 discovers languages from the classloader of the class that calls
 * {@code Engine.newBuilder}, and that API has no classloader parameter. The main
 * plugin jar no longer contains the polyglot runtime, so calling it there never
 * sees Python even when this jar is visible as a child loader. Running the call
 * here registers it.
 *
 * The main plugin reaches this class by reflection only, so it compiles and runs
 * without GraalPy on its classpath. {@link Failure} carries the source line of a
 * script error back across that boundary, because {@link PolyglotException} itself
 * is not on the main plugin's classpath.
 */
public final class GraalPyBridge {

    private GraalPyBridge() {
    }

    /** A live GraalPy context. The main plugin only needs bindings / eval / close. */
    public interface Session {
        void bind(String name, Object value);

        void eval(File file) throws Failure;

        void close();
    }

    /** A script failure, with the source line when GraalPy reported one. */
    public static final class Failure extends Exception {
        private final int line;

        public Failure(String message, int line, Throwable cause) {
            super(message, cause);
            this.line = line;
        }

        /** Source line, or {@code -1} when the failure has no source location. */
        public int line() {
            return line;
        }
    }

    public static Engine createEngine() {
        return Engine.newBuilder("python")
                .allowExperimentalOptions(true)
                .option("engine.WarnInterpreterOnly", "false")
                .build();
    }

    /** True when {@code engine} was built by {@link #createEngine()} and speaks Python. */
    public static boolean providesPython(Object engine) {
        return engine instanceof Engine && ((Engine) engine).getLanguages().containsKey("python");
    }

    public static Session open(Object engine) {
        if (!(engine instanceof Engine)) {
            throw new IllegalArgumentException("不是 GraalPy 引擎: " + engine);
        }
        Engine graal = (Engine) engine;
        // engine.WarnInterpreterOnly 是引擎级选项，共享 Engine 的 Context 上再设会被拒绝。
        Context context = Context.newBuilder("python")
                .engine(graal)
                .allowAllAccess(true)
                .build();
        // 具名的 public 静态类：主插件在另一个 classloader 里反射调用，
        // 匿名内部类会被 JDK 拒绝访问。
        return new PySession(context);
    }

    public static final class PySession implements Session {
        private final Context context;

        PySession(Context context) {
            this.context = context;
        }

        @Override
        public void bind(String name, Object value) {
            context.getBindings("python").putMember(name, value);
        }

        @Override
        public void eval(File file) throws Failure {
            try {
                context.eval(Source.newBuilder("python", file).build());
            } catch (PolyglotException error) {
                int line = -1;
                SourceSection section = error.getSourceLocation();
                if (section != null && section.isAvailable()) line = section.getStartLine();
                throw new Failure(error.getMessage(), line, error);
            } catch (Exception error) {
                throw new Failure(error.getMessage(), -1, error);
            }
        }

        @Override
        public void close() {
            context.close(true);
        }
    }
}
