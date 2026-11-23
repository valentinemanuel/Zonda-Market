package com.zondamarket.identity.domain;

import com.zondamarket.shared.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "account_address", schema = "zonda_market")
public class AccountAddress extends BaseEntity {
    @Column(nullable = false, updatable = false)
    private UUID accountId;

    @Column(nullable = false, length = 140)
    private String street;

    @Column(nullable = false, length = 80)
    private String city;

    @Column(nullable = false, length = 4)
    private String provinceCode;

    @Column(nullable = false, length = 8)
    private String postalCode;

    @Column(nullable = false, length = 2, updatable = false)
    private String country;

    @Column(length = 120)
    private String recipient;

    @Column(length = 32)
    private String phone;

    @Column(length = 250)
    private String deliveryDetails;

    @Column(name = "is_default", nullable = false)
    private boolean defaultAddress;

    protected AccountAddress() {}

    public AccountAddress(UUID accountId, String street, String city, String provinceCode, String postalCode) {
        this.accountId = accountId;
        this.country = "AR";
        update(street, city, provinceCode, postalCode);
    }

    public void update(String street, String city, String provinceCode, String postalCode) {
        this.street = street;
        this.city = city;
        this.provinceCode = provinceCode;
        this.postalCode = postalCode;
    }

    public void archive() {
        markDeleted();
    }

    public void updateDelivery(String recipient, String phone, String deliveryDetails) {
        this.recipient = recipient;
        this.phone = phone;
        this.deliveryDetails = deliveryDetails;
    }

    public void setDefaultAddress(boolean defaultAddress) {
        this.defaultAddress = defaultAddress;
    }

    public String getStreet() { return street; }
    public String getCity() { return city; }
    public String getProvinceCode() { return provinceCode; }
    public String getPostalCode() { return postalCode; }
    public String getCountry() { return country; }
    public String getRecipient() { return recipient; }
    public String getPhone() { return phone; }
    public String getDeliveryDetails() { return deliveryDetails; }
    public boolean isDefaultAddress() { return defaultAddress; }
}
