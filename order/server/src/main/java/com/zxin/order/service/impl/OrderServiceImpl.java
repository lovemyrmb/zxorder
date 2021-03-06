package com.zxin.order.service.impl;

import com.zxin.order.dataobject.OrderDetail;
import com.zxin.order.dataobject.OrderMaster;
import com.zxin.order.dto.CartDTO;
import com.zxin.order.dto.OrderDTO;
import com.zxin.order.enums.OrderStatusEnum;
import com.zxin.order.enums.PayStatusEnum;
import com.zxin.order.enums.ResultEnum;
import com.zxin.order.exception.OrderException;
import com.zxin.order.repository.OrderDetailRepository;
import com.zxin.order.repository.OrderMasterRepository;
import com.zxin.order.service.OrderService;
import com.zxin.order.utils.KeyUtil;
import com.zxin.product.client.ProductClient;
import com.zxin.product.common.DecreaseStockInput;
import com.zxin.product.common.ProductInfoOutput;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;

//import com.zxin.order.client.ProductClient;  //改成多模块之后，就不是导入这个了

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class OrderServiceImpl implements OrderService {

    // 这里需要注入两个 Repository

    @Autowired
    private OrderDetailRepository orderDetailRepository;
    @Autowired
    private OrderMasterRepository orderMasterRepository;

    @Autowired
    private ProductClient productClient; // 需要远程调用 ，注意这个是product中的ProductClient, 不是自己服务的

    @Override
    @Transactional
    public OrderDTO create(OrderDTO orderDTO) {

        /**
         *
         2、查询商品信息 (需要调用远程服务 Feign)；
         3、计算总价；
         4、扣库存(调用商品服务)；
         5、订单入库；  (这个可以做)
         */
        String orderId = KeyUtil.generateUniqueKey();

        //  2、查询商品信息 (需要调用远程服务 Feign)；
        List<String> productIdList = orderDTO.getOrderDetailList().stream()
                .map(OrderDetail::getProductId)
                .collect(Collectors.toList());

//        List<ProductInfo> productInfoList = productClient.listForOrder(productIdList);//需要传递ProductId <List>
        // 改成多模块之后，上面那句变成这句
        List<ProductInfoOutput> productInfoList = productClient.listForOrder(productIdList);


        // 3、计算总价；
        BigDecimal orderAmount = new BigDecimal(BigInteger.ZERO);
        for(OrderDetail orderDetail : orderDTO.getOrderDetailList()){

            // 和查询到的商品的单价
//            for(ProductInfo productInfo : productInfoList){
                for(ProductInfoOutput productInfo : productInfoList){ // 改成多模块
                    // 判断相等才计算
                if(productInfo.getProductId().equals(orderDetail.getProductId())){
                    // 单价 productInfo.getProductPrice()
                    // 数量 orderDetail.getProductQuantity(
                    orderAmount = productInfo.getProductPrice()
                            .multiply(new BigDecimal(orderDetail.getProductQuantity()))
                            .add(orderAmount);

                    //给订单详情赋值
                    BeanUtils.copyProperties(productInfo, orderDetail); //这种拷贝要注意值为null也会拷贝
                    orderDetail.setOrderId(orderId);
                    orderDetail.setDetailId(KeyUtil.generateUniqueKey()); // 每个Detail的Id

                    // 订单详情入库
                    orderDetailRepository.save(orderDetail);
                }
            }
        }

        // 4、扣库存(调用商品服务)；
//        List<CartDTO> cartDTOList = orderDTO.getOrderDetailList().stream()
//                .map(e -> new CartDTO(e.getProductId(), e.getProductQuantity()))
//                .collect(Collectors.toList());
//        productClient.decreaseStock(cartDTOList); //订单主库入库了
        // 上面是多模块之前，　下面是多模块之后
        List<DecreaseStockInput> decreaseStockInputList = orderDTO.getOrderDetailList().stream()
                .map(e -> new DecreaseStockInput(e.getProductId(), e.getProductQuantity()))
                .collect(Collectors.toList());
        productClient.decreaseStock(decreaseStockInputList);


        // 5、订单入库 (OrderMaster)
        OrderMaster orderMaster = new OrderMaster();
        orderDTO.setOrderId(orderId); // 生成唯一的订单逐渐(订单号)
        BeanUtils.copyProperties(orderDTO, orderMaster); // 将orderDTO拷贝到orderMaster
        orderMaster.setOrderAmount(orderAmount); // 设置订单总金额
        orderMaster.setOrderStatus(OrderStatusEnum.NEW.getCode());
        orderMaster.setPayStatus(PayStatusEnum.WAIT.getCode());

        orderMasterRepository.save(orderMaster); //存在主仓库
        return orderDTO;
    }

    // 完结订单
    @Override
    @Transactional
    public OrderDTO finish(String orderId) {

        // 1、先查询订单
        Optional<OrderMaster> orderMasterOptional = orderMasterRepository.findById(orderId);
        if(!orderMasterOptional.isPresent()){
            throw new OrderException(ResultEnum.ORDER_NOT_EXIST);
        }


        // 2、判断订单状态 (并不是所有订单都可以定为完结)
        OrderMaster orderMaster = orderMasterOptional.get();
        if (OrderStatusEnum.NEW.getCode() != orderMaster.getOrderStatus()) {
            throw new OrderException(ResultEnum.ORDER_STATUS_ERROR);
        }

        //3. 修改订单状态为完结
        orderMaster.setOrderStatus(OrderStatusEnum.FINISHED.getCode());
        orderMasterRepository.save(orderMaster);

        //查询订单详情 ，为了返回orderDTO
        List<OrderDetail> orderDetailList = orderDetailRepository.findByOrderId(orderId);
        if (CollectionUtils.isEmpty(orderDetailList)) {
            throw new OrderException(ResultEnum.ORDER_DETAIL_NOT_EXIST);
        }
        OrderDTO orderDTO = new OrderDTO();
        BeanUtils.copyProperties(orderMaster, orderDTO);
        orderDTO.setOrderDetailList(orderDetailList);

        return orderDTO;
    }
}
