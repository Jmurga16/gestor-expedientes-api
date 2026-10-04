package com.gestionexpedientes.file.service;

import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class BlobStorage {

    private final BlobServiceClient serviceClient;
    private final String accountUrl;
    private final String publicUrl;

    public BlobStorage(@Value("${azure.storage.connection-string:}") String connectionString,
                       @Value("${azure.storage.account-name:}") String accountName,
                       @Value("${azure.storage.account-key:}") String accountKey,
                       @Value("${azure.storage.public-endpoint:}") String publicEndpoint) {
        String cadena = StringUtils.hasText(connectionString)
                ? connectionString
                : String.format("DefaultEndpointsProtocol=https;AccountName=%s;AccountKey=%s;EndpointSuffix=core.windows.net",
                        accountName, accountKey);
        this.serviceClient = new BlobServiceClientBuilder().connectionString(cadena).buildClient();
        this.accountUrl = sinBarraFinal(serviceClient.getAccountUrl());
        this.publicUrl = StringUtils.hasText(publicEndpoint) ? sinBarraFinal(publicEndpoint) : accountUrl;
    }

    public BlobContainerClient container(String name) {
        return serviceClient.getBlobContainerClient(name);
    }

    public BlobClient blob(String container, String blobName) {
        return container(container).getBlobClient(blobName);
    }

    public String publicUrl() {
        return publicUrl;
    }

    public String publicUrl(BlobClient blob) {
        String url = blob.getBlobUrl();
        return url.startsWith(accountUrl) ? publicUrl + url.substring(accountUrl.length()) : url;
    }

    private static String sinBarraFinal(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
