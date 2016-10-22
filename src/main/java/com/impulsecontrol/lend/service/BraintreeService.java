package com.impulsecontrol.lend.service;

import com.braintreegateway.BraintreeGateway;
import com.braintreegateway.Customer;
import com.braintreegateway.CustomerRequest;
import com.braintreegateway.Environment;
import com.braintreegateway.MerchantAccount;
import com.braintreegateway.MerchantAccountRequest;
import com.braintreegateway.Result;
import com.braintreegateway.Transaction;
import com.braintreegateway.ValidationError;
import com.braintreegateway.WebhookNotification;
import com.impulsecontrol.lend.exception.InternalServerException;
import com.impulsecontrol.lend.exception.NotFoundException;
import com.impulsecontrol.lend.firebase.CcsServer;
import com.impulsecontrol.lend.firebase.FirebaseUtils;
import com.impulsecontrol.lend.model.User;
import com.mongodb.BasicDBObject;
import com.mongodb.DBObject;
import org.json.JSONObject;
import org.mongojack.JacksonDBCollection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Created by kerrk on 10/16/16.
 */
public class BraintreeService {

    private String merchantId;
    private String publicKey;
    private String privateKey;
    private CcsServer ccsServer;
    private static BraintreeGateway gateway;
    private static final Logger LOGGER = LoggerFactory.getLogger(BraintreeService.class);
    private JacksonDBCollection<User, String> userCollection;

    public BraintreeService(String merchantId, String publicKey, String privateKey,
                            JacksonDBCollection<User, String> userCollection, CcsServer ccsServer) {
        this.merchantId = merchantId;
        this.publicKey = publicKey;
        this.privateKey = privateKey;
        this.gateway = new BraintreeGateway(Environment.SANDBOX, merchantId, publicKey, privateKey);
        this.userCollection = userCollection;
        this.ccsServer = ccsServer;
    }


    public Result<Transaction> doPayment(User user, com.impulsecontrol.lend.model.Transaction transaction) {
        /*TransactionRequest request = new TransactionRequest()
                .amount(BigDecimal.valueOf(transaction.getFinalPrice()))
                .paymentMethodNonce(user.getPaymentMethodNonce())
                .options()
                .submitForSettlement(true)
                .done();

        Result<com.braintreegateway.Transaction> result = gateway.transaction().sale(request);*/
        return null;

    }

    public String getBraintreeClientToken() {
        return gateway.clientToken().generate();
    }

    public MerchantAccount updateMerchantAccount(MerchantAccountRequest request, String merchantId) {
        Result<MerchantAccount> result = gateway.merchantAccount().update(merchantId, request);
        return handleMerchantAccountResult(result, request);
    }

    public MerchantAccount createNewMerchantAccount(MerchantAccountRequest request) {
        request.masterMerchantAccountId(merchantId);
        Result<MerchantAccount> result = gateway.merchantAccount().create(request);
        return handleMerchantAccountResult(result, request);
    }

    public Customer saveOrUpdateCustomer(CustomerRequest request, String customerId) {
        Result<Customer> result = customerId != null ? gateway.customer().update(customerId, request) :
                gateway.customer().create(request);
        result.isSuccess();
        if (result.isSuccess()) {
            Customer customer = result.getTarget();
            LOGGER.info("Successfully created/updated customer account request: " + request.toQueryString());
            return customer;
        } else {
            String msg = "Unable to create/update customer request, got message: " + result.getMessage();
            LOGGER.error(msg);
            LOGGER.error(request.toString());
            if (result.getErrors() != null) {
                result.getErrors().getAllValidationErrors().forEach(e -> {
                    LOGGER.error("***braintree attribute: " + e.getAttribute() + "  **error: " + e.getMessage());
                });
            }
            throw new InternalServerException(msg);
        }
    }

    public void handleWebhookResponse(String signature, String payload) {
        WebhookNotification notification = gateway.webhookNotification().parse(signature, payload);
        if (notification.getKind() == WebhookNotification.Kind.SUB_MERCHANT_ACCOUNT_APPROVED ||
                notification.getKind() == WebhookNotification.Kind.SUB_MERCHANT_ACCOUNT_DECLINED) {
            String merchantId = notification.getMerchantAccount().getId();
            DBObject searchByMerchantId = new BasicDBObject("merchantId", merchantId);
            User user = userCollection.findOne(searchByMerchantId);
            if (user == null) {
                String msg = "Could not find user associated with merchant id [" + merchantId + "]";
                LOGGER.error(msg);
                throw new NotFoundException(msg);
            }
            user.setMerchantStatus(notification.getMerchantAccount().getStatus().toString());
            userCollection.save(user);
            if (notification.getKind() == WebhookNotification.Kind.SUB_MERCHANT_ACCOUNT_DECLINED) {
                JSONObject n = new JSONObject();
                n.put("title", "Merchant Account Declined");
                String errorMessage = "";
                for (ValidationError e:notification.getErrors().getAllValidationErrors()) {
                    errorMessage += "**attribute: " + e.getAttribute() + " **error: " + e.getMessage() + "\n";

                }
                LOGGER.error("Merchant account declined for user [" + user.getId() + "]: " + errorMessage);
                n.put("message", errorMessage);
                n.put("type", "merchant_account_status");
                User recipient = userCollection.findOneById(user.getId());
                FirebaseUtils.sendFcmMessage(recipient, null, n, ccsServer);
            } else {
                JSONObject n = new JSONObject();
                n.put("title", "Merchant Account Approved");
                n.put("message", "You can now create offers and earn money through Nearby!");
                n.put("type", "merchant_account_status");
                User recipient = userCollection.findOneById(user.getId());
                FirebaseUtils.sendFcmMessage(recipient, null, n, ccsServer);
            }
        }
    }

    private MerchantAccount handleMerchantAccountResult(Result<MerchantAccount> result, MerchantAccountRequest request) {
        if (result.isSuccess()) {
            MerchantAccount ma = result.getTarget();
            LOGGER.info("Successfully created/updated a merchant account request: " + request.toQueryString());
            return ma;
        } else {
            String msg = "Unable to create/update braintree merchant account request, got message: " + result.getMessage();
            LOGGER.error(msg);
            LOGGER.error(request.toString());
            if (result.getErrors() != null) {
                result.getErrors().getAllValidationErrors().forEach(e -> {
                    LOGGER.error("***braintree attribute: " + e.getAttribute() + "  **error: " + e.getMessage());
                });
            }
            throw new InternalServerException(msg);
        }
    }
}
