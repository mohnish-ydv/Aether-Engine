package com.mohnishraj.aether.platform.android.net

import com.mohnishraj.aether.core.net.dns.DnsAnswer
import com.mohnishraj.aether.core.net.dns.DnsResolver
import com.mohnishraj.aether.core.net.model.NetworkFailure
import com.mohnishraj.aether.core.net.model.NetworkFailureKind
import com.mohnishraj.aether.core.net.model.NetworkResult
import java.net.InetAddress

class AndroidDnsResolver : DnsResolver {
    override fun resolve(host: String): NetworkResult<DnsAnswer> = try {
        val addresses = InetAddress.getAllByName(host).mapNotNull { it.hostAddress }.distinct()
        if (addresses.isEmpty()) NetworkResult.Failure(NetworkFailure(NetworkFailureKind.DNS, "No addresses returned for $host"))
        else NetworkResult.Success(DnsAnswer(host, addresses, System.currentTimeMillis(), false))
    } catch (error: Exception) {
        NetworkResult.Failure(NetworkFailure(NetworkFailureKind.DNS, error.message ?: "DNS lookup failed", causeType = error::class.java.name))
    }
}
