package com.utewedding.app.dto.request;

public record ShopPayoutDestinationCommand(String bankCode, String accountNumber,
                                           String accountHolderName, String passwordConfirmation) { }
