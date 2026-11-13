package vn.edu.hsf302.lawrag.ingestion.service;

import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

@Component
public class SystemHostAddressResolver implements HostAddressResolver {

    @Override
    public List<InetAddress> resolve(String host) throws UnknownHostException {
        return List.of(InetAddress.getAllByName(host));
    }
}
