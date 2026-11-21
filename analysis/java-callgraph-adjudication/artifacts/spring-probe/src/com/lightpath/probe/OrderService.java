package com.lightpath.probe;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

public interface PaymentGateway { void charge(int amount); }

@Service
public class StripeGateway implements PaymentGateway {
    public void charge(int amount) { }
}

@Service
public class PaypalGateway implements PaymentGateway {
    public void charge(int amount) { }
}

@Service
public class OrderService {
    private final PaymentGateway gateway;

    @Autowired
    public OrderService(@Qualifier("stripeGateway") PaymentGateway gateway) {
        this.gateway = gateway;
    }

    public void checkout(int amount) {
        gateway.charge(amount);
    }
}

@RestController
public class OrderController {
    private final OrderService orderService;

    @Autowired
    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @GetMapping("/orders")
    public String orders() {
        orderService.checkout(42);
        return "ok";
    }
}
