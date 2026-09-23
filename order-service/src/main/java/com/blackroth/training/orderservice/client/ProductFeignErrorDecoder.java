package com.blackroth.training.orderservice.client;
import com.blackroth.training.orderservice.exception.OrderExceptions;
import feign.Response; import feign.codec.ErrorDecoder;
public class ProductFeignErrorDecoder implements ErrorDecoder
{
    private final ErrorDecoder defaultDecoder=new Default();
    @Override public Exception decode(String methodKey,Response response)
    {
        if(response.status()==404)
            return new OrderExceptions.ProductNotFound("Product not found");
        if(response.status()>=500)
            return new OrderExceptions.ProductServiceUnavailable("Product Service returned "+response.status());
        return defaultDecoder.decode(methodKey,response);
    }
}
