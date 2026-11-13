package vn.edu.hsf302.lawrag.ingestion.service;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

/** DNS boundary so SSRF validation is testable without a network. */
public interface HostAddressResolver {

    List<InetAddress> resolve(String host) throws UnknownHostException;
}
