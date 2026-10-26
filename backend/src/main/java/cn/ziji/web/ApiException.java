package cn.ziji.web;

public class ApiException extends RuntimeException {
    private final int status;
    public ApiException(int status,String message){super(message);this.status=status;}
    public int status(){return status;}
    public static ApiException conflict(){return new ApiException(409,"这条记录已被其他操作修改，请刷新后重试");}
}
