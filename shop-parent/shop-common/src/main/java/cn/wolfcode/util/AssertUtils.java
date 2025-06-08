package cn.wolfcode.util;

import cn.wolfcode.common.exception.BusinessException;
import cn.wolfcode.common.web.CodeMsg;

public class AssertUtils {

    public static void notNUll(Object object, String msg) {
        if (object == null) {
            throw new BusinessException(new CodeMsg(501,msg));
        }
    }

    public static void isTrue(boolean ret, String msg) {
        if (!ret) {
            throw new BusinessException(new CodeMsg(501,msg));
        }
    }
}
