import java.io.File;
import java.security.KeyStore;
import java.security.UnrecoverableKeyException;
import java.util.Collections;

/**
 * 预检 release 签名材料，把 AGP 那句无信息量的
 * "KeytoolException: Failed to read key *** from store ...: null"
 * 拆成能直接指认哪个 Secret 配错的三种失败。
 *
 * <p>keytool 不能承担这件事：对 PKCS12 keystore，{@code -certreq} / {@code -importkeystore}
 * 在 {@code -keypass} 不对时会静默退回库口令并返回 0，所以口令错了也测不出来。这里直接调
 * {@link KeyStore#getKey}，与 AGP 走的是同一条路径。
 *
 * <p>输入走环境变量（避免口令出现在进程命令行里）：
 * KS_PATH / KS_PASSWORD / KS_ALIAS / KEY_PASSWORD。
 *
 * <p>私钥解开时向 stdout 打印用的是哪个口令：{@code key}（KEY_PASSWORD）或
 * {@code store}（KS_PASSWORD，PKCS12 的常态）。退出码：10 打不开库，11 别名不存在，
 * 12 两个口令都解不开私钥。
 */
public final class SigningProbe {

    public static void main(String[] args) {
        String path = env("KS_PATH");
        String storePassword = env("KS_PASSWORD");
        String alias = env("KS_ALIAS");
        String keyPassword = env("KEY_PASSWORD");

        KeyStore keyStore;
        try {
            // 这个重载会按文件内容自动判定 JKS / PKCS12，不用事先知道格式。
            keyStore = KeyStore.getInstance(new File(path), storePassword.toCharArray());
        } catch (Exception e) {
            System.err.println("打不开 keystore：" + describe(e));
            System.exit(10);
            return;
        }

        try {
            if (!keyStore.containsAlias(alias)) {
                System.err.println("keystore 里没有别名 '" + alias + "'。实际包含的条目：");
                for (String each : Collections.list(keyStore.aliases())) {
                    System.err.println("  - " + each);
                }
                System.exit(11);
                return;
            }
        } catch (Exception e) {
            System.err.println("枚举别名失败：" + describe(e));
            System.exit(10);
            return;
        }

        if (unlocks(keyStore, alias, keyPassword)) {
            System.out.println("key");
            return;
        }
        if (unlocks(keyStore, alias, storePassword)) {
            System.out.println("store");
            return;
        }
        System.err.println("库口令能打开 keystore、别名 '" + alias + "' 也存在，但两个口令都解不开它的私钥。");
        System.exit(12);
    }

    private static boolean unlocks(KeyStore keyStore, String alias, String password) {
        try {
            return keyStore.getKey(alias, password.toCharArray()) != null;
        } catch (UnrecoverableKeyException e) {
            return false;
        } catch (Exception e) {
            System.err.println("读取私钥时出现非口令类错误：" + describe(e));
            return false;
        }
    }

    private static String env(String name) {
        String value = System.getenv(name);
        if (value == null || value.isEmpty()) {
            System.err.println("缺少环境变量 " + name);
            System.exit(10);
        }
        return value;
    }

    /** 异常链末端的信息量最大，且口令类失败的 message 常常是 null。 */
    private static String describe(Throwable t) {
        Throwable root = t;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        String message = root.getMessage();
        return root.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }

    private SigningProbe() {
    }
}
