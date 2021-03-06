package com.chainup.api;

import com.maxmind.db.CHMCache;
import com.maxmind.geoip2.DatabaseReader;
import com.maxmind.geoip2.model.CityResponse;
import com.maxmind.geoip2.record.City;
import com.maxmind.geoip2.record.Country;
import com.maxmind.geoip2.record.Location;
import com.rabbitmq.client.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URL;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@RestController
public class ApiAct {

    private static final Logger logger = LogManager.getLogger(ApiAct.class);

    @Autowired
    private RedisTemplate redisTemplate;

    @Autowired
    private ConnectionFactory connectionFactory;

    @RequestMapping("load")
    public Map load(HttpServletRequest request, HttpServletResponse response){
        URL url = ApiAct.class.getClassLoader().getResource("GeoLite2-City.mmdb");
        DatabaseReader reader = null;
        Connection connection = null;
        try {

            String ip = "144.34.237.147";

            reader = new DatabaseReader.Builder(url.openStream()).withCache(new CHMCache()).build();
            InetAddress ipAddress = InetAddress.getByName(ip);

            CityResponse cityResponse = reader.city(ipAddress);

            Country country = cityResponse.getCountry();
            logger.info("国家={}",country.getNames().get("zh-CN")); // '美国''

            City city = cityResponse.getCity();
            logger.info("城市={}",city.getNames().get("zh-CN")); // 'Minneapolis'

            Location location = cityResponse.getLocation();
            logger.info("经度={}，纬度={}",location.getLatitude(),location.getLongitude());

            redisTemplate.opsForValue().set(ip,city.getNames());
            redisTemplate.expire(ip,60, TimeUnit.MINUTES);

            connection = connectionFactory.newConnection();
            Channel channel = connection.createChannel();

            AMQP.BasicProperties basicProperties = new AMQP.BasicProperties.Builder().expiration("180000").build();

            //新建交换机
            channel.exchangeDeclare("okay", BuiltinExchangeType.TOPIC,false,false,null);
            //创建队列
            AMQP.Queue.DeclareOk declareOk = channel.queueDeclare("okay_queue",false,false,false,null);
            channel.queueBind("okay_queue", "okay", "okay");
            channel.confirmSelect();
            channel.addConfirmListener(new ConfirmListener() {
                @Override
                public void handleAck(long deliveryTag, boolean multiple) throws IOException {
                    System.out.println("----------Ack----------");
                    System.out.println(deliveryTag);
                    System.out.println(multiple);
                }

                @Override
                public void handleNack(long deliveryTag, boolean multiple) throws IOException {
                    System.out.println("----------Nack----------");
                    System.out.println(deliveryTag);
                    System.out.println(multiple);
                }
            });

            channel.basicPublish("okay","okay",true, basicProperties, "hello world, i'm golang".getBytes("UTF-8"));

            if(channel.waitForConfirms()) {
                logger.info("消息发送成功");
            }

            return city.getNames();
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            try {
                reader.close();
                connection.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        return null;
    }
}
