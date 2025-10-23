package com.instana.robotshop.shipping;

import java.util.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.instana.sdk.annotation.Span;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.data.domain.Sort;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import com.instana.sdk.support.SpanSupport;



@RestController
public class Controller {
    private static final Logger logger = LoggerFactory.getLogger(Controller.class);

    private String CART_URL = String.format("http://%s/shipping/", getenv("CART_ENDPOINT", "cart"));

    public static List bytesGlobal = Collections.synchronizedList(new ArrayList<byte[]>());

    @Autowired
    private CityRepository cityrepo;

    @Autowired
    private CodeRepository coderepo;

    private String getenv(String key, String def) {
        String val = System.getenv(key);
        val = val == null ? def : val;

        return val;
    }

    @GetMapping(path = "/memory")
    public int memory() {
        byte[] bytes = new byte[1024 * 1024 * 25];
        Arrays.fill(bytes,(byte)8);
        bytesGlobal.add(bytes);

        return bytesGlobal.size();
    }

    @GetMapping(path = "/free")
    public int free() {
        bytesGlobal.clear();

        return bytesGlobal.size();
    }

    @GetMapping("/health")
    public String health() {
        return "OK";
    }

    @GetMapping("/count")
    public String count() {
        long count = cityrepo.count();

        return String.valueOf(count);
    }

    @GetMapping("/codes")
    public Iterable<Code> codes() {
        logger.info("all codes");

        Iterable<Code> codes = coderepo.findAll(Sort.by(Sort.Direction.ASC, "name"));

        return codes;
    }

    @GetMapping("/cities/{code}")
    public List<City> cities(@PathVariable String code) {
        logger.info("cities by code {}", code);

        List<City> cities = cityrepo.findByCode(code);

        return cities;
    }

    @GetMapping("/match/{code}/{text}")
    public List<City> match(@PathVariable String code, @PathVariable String text) {
        logger.info("match code {} text {}", code, text);

        if (text.length() < 3) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }

        List<City> cities = cityrepo.match(code, text);
        /*
         * This is a dirty hack to limit the result size
         * I'm sure there is a more spring boot way to do this
         * TODO - neater
         */
        if (cities.size() > 10) {
            cities = cities.subList(0, 9);
        }

        return cities;
    }

    @GetMapping("/calc/{id}")
    public Ship caclc(@PathVariable long id) {
        double homeLatitude = 51.164896;
        double homeLongitude = 7.068792;

        logger.info("Calculation for {}", id);

        City city = cityrepo.findById(id);
        if (city == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "city not found");
        }

        Calculator calc = new Calculator(city);
        long distance = calc.getDistance(homeLatitude, homeLongitude);
        // avoid rounding
        double cost = Math.rint(distance * 5) / 100.0;
        Ship ship = new Ship(distance, cost);
        logger.info("shipping {}", ship);

        return ship;
    }

    // enforce content type
//    @PostMapping(path = "/confirm/{id}", consumes = "application/json", produces = "application/json")
//    public String confirm(@PathVariable String id, @RequestBody String body) {
//        logger.info("confirm id: {}", id);
//        logger.info("body {}", body);
//
//        CartHelper helper = new CartHelper(CART_URL);
//        String cart = helper.addToCart(id, body);
//
//        if (cart.equals("")) {
//            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "cart not found");
//        }
//
//        return cart;
//    }

//    @PostMapping(path = "/confirm/{id}", consumes = "application/json", produces = "application/json")
//    public String confirm(@PathVariable String id, @RequestBody String body) {
//        long startTime = System.currentTimeMillis();
//        logger.info("confirm id: {}", id);
//        logger.info("body {}", body);
//
//        CartHelper helper = new CartHelper(CART_URL);
//        String cart = helper.addToCart(id, body);
//
//        if (cart.equals("")) {
//            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "cart not found");
//        }
//
//        // --- Tambahkan Instrumentation di sini ---
//        try {
//            // contoh sederhana, bisa diganti dengan JSON parser
//            SpanSupport.annotate(Span.Type.valueOf("business"), "transaction", "id", id);
//            SpanSupport.annotate(Span.Type.valueOf("business"), "transaction", "request_body", body);
//            SpanSupport.annotate(Span.Type.valueOf("business"), "transaction", "response_body", cart);
//            SpanSupport.annotate(Span.Type.valueOf("business"), "transaction", "latency_ms",
//                    String.valueOf(System.currentTimeMillis() - startTime));
//        } catch (Exception e) {
//            logger.warn("Instrumentation failed: {}", e.getMessage());
//        }
//        // --- End Instrumentation ---
//
//        return cart;
//    }

    // ================================
    // CUSTOMIZED FOR BUSINESS MONITORING
    // ================================
    @PostMapping(path = "/confirm/{id}", consumes = "application/json", produces = "application/json")
    public String confirm(@PathVariable String id, @RequestBody String body) {
        long startTime = System.currentTimeMillis();
        logger.info("confirm id: {}", id);
        logger.info("body {}", body);

        CartHelper helper = new CartHelper(CART_URL);
        String cartResponse = helper.addToCart(id, body);

        if (cartResponse.equals("")) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "cart not found");
        }

        // -------------------------------
        // Instana Business Annotation
        // -------------------------------
        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode node = mapper.readTree(cartResponse);

            String responseCode = node.has("responseCode") ? node.get("responseCode").asText() : "UNKNOWN";
            String responseMessage = node.has("responseMessage") ? node.get("responseMessage").asText() : "N/A";
            String denom = node.has("denom") ? node.get("denom").asText() : "N/A";
            long latency = System.currentTimeMillis() - startTime;

            // Tambahkan metadata ke trace Instana
            SpanSupport.annotate(Span.Type.valueOf("business"), "transaction", "transaction_id", id);
            SpanSupport.annotate(Span.Type.valueOf("business"), "transaction", "denom", denom);
            SpanSupport.annotate(Span.Type.valueOf("business"), "transaction", "response_code", responseCode);
            SpanSupport.annotate(Span.Type.valueOf("business"), "transaction", "response_message", responseMessage);
            SpanSupport.annotate(Span.Type.valueOf("business"), "transaction", "latency_ms", String.valueOf(latency));

            logger.info("Instana annotation added: denom={}, code={}, msg={}, latency={}ms",
                    denom, responseCode, responseMessage, latency);

        } catch (Exception e) {
            logger.error("Failed to annotate Instana trace: {}", e.getMessage());
        }

        return cartResponse;
    }

    // Tambahan: endpoint baru untuk uji response body
    @Span(value = "shipping.dispatch") // membuat custom span di Instana
    @PostMapping(path = "/dispatch/{orderid}", produces = "application/json")
    public Map<String, Object> dispatch(@PathVariable String orderid, @RequestBody(required = false) String payload) {
        long start = System.currentTimeMillis();

        logger.info("Dispatch called for order: {}", orderid);

        // Simulasi proses shipping
        Map<String, Object> response = new HashMap<>();
        response.put("orderId", orderid);
        response.put("status", "SHIPPED");
        response.put("responseCode", "200");
        response.put("message", "Order dispatched successfully");
        response.put("payload", payload);
        response.put("timestamp", System.currentTimeMillis());

        // Hitung latency
        long latency = System.currentTimeMillis() - start;

        // ✅ Tambahkan custom tags ke Instana trace
        SpanSupport.annotate("shipping.orderId", orderid);
        SpanSupport.annotate("shipping.responseCode", "200");
        SpanSupport.annotate("shipping.latency", String.valueOf(latency));
        SpanSupport.annotate("shipping.message", "Order dispatched successfully");

        logger.info("Response: {}", response);
        return response;
    }
}
