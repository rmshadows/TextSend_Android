package RandomNumber;

import android.os.Build;

import java.security.SecureRandom;
import java.util.Random;
import java.util.stream.IntStream;

/**
 * Android min:24 android 7
 * <a href="https://www.techiedelight.com/zh/generate-random-integers-specified-range-java/">...</a>
 */
public class RandomNumber {
    /**
     * 在 [min, max] 范围内生成一个伪随机整数
     *
     * @param min 范围的起始值(包括)
     * @param max 范围的结束值(包括)
     */
    public static int randomInt(int min, int max) {
        if (min > max) {
            throw new IllegalArgumentException("Invalid range");
        }
        Random r = new Random();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            IntStream is = r.ints(min, (max + 1));// IntStream
            return is.findFirst().getAsInt();
        }else {
            return secureRandomInt(min, max);
        }
    }

    /**
     * 在 [min, max] 范围内生成一个伪随机整数
     *
     * @param min 范围的起始值(包括)
     * @param max 范围的结束值(包括)
     */
    public static int secureRandomInt(int min, int max) {
        if (min > max || (max - min + 1 > Integer.MAX_VALUE)) {
            throw new IllegalArgumentException("Invalid range");
        }
        // 获取一个 `SecureRandom` 实例并使用种子字节为实例播种
        // 使用 generateSeed 方法
        SecureRandom random = new SecureRandom();
        random.setSeed(random.generateSeed(20));
        // nextInt() 继承自 java.util.Random 类
        return random.nextInt((max - min) + 1) + min;
    }

    /**
     * 在 [min, max] 范围内生成一个伪随机整数
     *
     * @param min 范围的起始值(包括)
     * @param max 范围的结束值(包括)
     */
    public static int mathRandomInt(int min, int max) {
        if (min > max) {
            throw new IllegalArgumentException("Invalid range");
        }
        double rand = Math.random();
        return (int) (rand * ((max - min) + 1)) + min;
    }
}
