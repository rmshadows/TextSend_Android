package utils;

import RandomNumber.RandomNumber;
import cn.rmshadows.textsend.MainActivity;

import java.util.LinkedList;

public class MessageCrypto {
    // 分隔符
    public static final String MSG_SPLITOR = "☯☯";

    /**
     * 加密字符串
     *
     * @param string 字符串
     */
    public static String tsEncryptString(String string) {
        AES_Utils.AES_CFB cfb = new AES_Utils.AES_CFB(MainActivity.AES_TOKEN, "ES", 32);
        return cfb.encrypt(string);
    }

    /**
     * 解密字符串
     *
     * @param string 字符串
     */
    public static String tsDecryptString(String string) {
        AES_Utils.AES_CFB cfb = new AES_Utils.AES_CFB(MainActivity.AES_TOKEN, "ES", 32);
        return cfb.decrypt(string);
    }

    /**
     * 加密明文GsonMessage (注意：不会对Data进行加密！Data加密请在Message中进行！)
     *
     * @param clearGsonMessage 明文gm
     */
    public static GsonMessage gsonMessageEncrypt(GsonMessage clearGsonMessage) {
        try {
            String id = String.format("%s%s%s", clearGsonMessage.getId(), MSG_SPLITOR, randomInt());
            id = tsEncryptString(id);
            LinkedList<String> data = clearGsonMessage.getData();
            String notes = String.format("%s%s%s", clearGsonMessage.getNotes(), MSG_SPLITOR, randomInt());
            notes = tsEncryptString(notes);
            GsonMessage encryptedGm = new GsonMessage(id, data, notes);
            System.out.print(clearGsonMessage);
            System.out.print("  ->  ");
            System.out.println(encryptedGm);
            return encryptedGm;
        } catch (Exception e) {
            e.printStackTrace();
            System.out.println("Gson Message JSON加密失败");
            return null;
        }
    }

    /**
     * 解密加密的GsonMessage到明文 (Data也会被解密成明文)
     *
     * @param encryptedGsonMessage 加密的GM
     */
    public static GsonMessage gsonMessageDecrypt(GsonMessage encryptedGsonMessage) {
        try {
            String id = tsDecryptString(encryptedGsonMessage.getId()).split(MSG_SPLITOR)[0];
            LinkedList<String> data = new LinkedList<>();
            // 解密Data
            for (String es : encryptedGsonMessage.getData()) {
                data.add(tsDecryptString(es));
            }
            String notes = tsDecryptString(encryptedGsonMessage.getNotes()).split(MSG_SPLITOR)[0];
            return new GsonMessage(id, data, notes);
        } catch (Exception e) {
            e.printStackTrace();
            System.out.println("Gson Message JSON解密失败");
            return null;
        }
    }

    /**
     * 返回随机数 0~5000
     */
    private static int randomInt() {
        return RandomNumber.secureRandomInt(0, 5000);
    }
}
