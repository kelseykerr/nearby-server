package com.impulsecontrol.lend.service;

import com.impulsecontrol.lend.dto.ResponseDto;
import com.impulsecontrol.lend.exception.BadRequestException;
import com.impulsecontrol.lend.exception.UnauthorizedException;
import com.impulsecontrol.lend.firebase.CcsServer;
import com.impulsecontrol.lend.model.Message;
import com.impulsecontrol.lend.model.Request;
import com.impulsecontrol.lend.model.Response;
import com.impulsecontrol.lend.model.User;
import com.mongodb.BasicDBObject;
import org.json.JSONObject;
import org.mongojack.DBCursor;
import org.mongojack.JacksonDBCollection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.IllegalArgumentException;
import java.util.Date;
import java.util.List;

/**
 * Created by kerrk on 9/3/16.
 */
public class ResponseService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ResponseService.class);

    private JacksonDBCollection<Request, String> requestCollection;
    private JacksonDBCollection<Response, String> responseCollection;
    private JacksonDBCollection<User, String> userCollection;
    private CcsServer ccsServer;

    public ResponseService() {

    }

    public ResponseService(JacksonDBCollection<Request, String> requestCollection,
                           JacksonDBCollection<Response, String> responseCollection,
                           JacksonDBCollection<User, String> userCollection,
                                   CcsServer ccsServer) {
        this.requestCollection = requestCollection;
        this.responseCollection = responseCollection;
        this.userCollection = userCollection;
        this.ccsServer = ccsServer;
    }

    public Response transformResponseDto(ResponseDto dto, Request request, User seller) {
        if (request.getStatus() != Request.Status.OPEN) {
            String msg = "Cannot create this offer because the request was recently fulfilled or closed.";
            LOGGER.info(msg);
            throw new BadRequestException(msg);
        }
        if (request.getExpireDate() != null && request.getExpireDate().before(new Date())) {
            request.setStatus(Request.Status.CLOSED);
            requestCollection.save(request);
            String msg = "Cannot create this offer because the request was recently closed.";
            LOGGER.info(msg);
            throw new BadRequestException(msg);
        }
        Response response = new Response();
        response.setResponseTime(new Date());
        response.setSellerStatus(Response.SellerStatus.OFFERED);
        response.setResponseStatus(Response.Status.PENDING);
        response.setRequestId(request.getId());
        response.setSellerId(seller.getId());
        populateResponse(response, dto);
        if (dto.messages != null && dto.messages.size() > 0 && dto.messages.get(0).getContent() != null) {
            Message message = new Message();
            message.setTimeSent(new Date());
            message.setSenderId(seller.getId());
            message.setContent(dto.messages.get(0).getContent());
            response.addMessage(message);
            // send message
            User recipient = userCollection.findOneById(request.getUser().getId());
            sendFcmMessage(recipient, seller, dto.messages.get(0).getContent());

        }
        responseCollection.insert(response);
        return response;
    }

    private void sendFcmMessage(User recipient, User sender, String message) {
        if (recipient.getFcmRegistrationId() == null) {
            String msg = "could not send message to [" + recipient.getFirstName() + "] " +
                    "because they have not allowed message.";
            LOGGER.error(msg);
            //TODO: make this general internal server exception
            throw new BadRequestException(msg);
        }
        String messageId = CcsServer.nextMessageId();
        JSONObject payload = new JSONObject();
        payload.put("message", message);
        String jsonMessage = CcsServer.createJsonMessage(recipient.getFcmRegistrationId(), messageId, payload,
                null, null, null);
        try {
            Boolean sent = ccsServer.sendDownstreamMessage(jsonMessage);
            if (sent) {
                LOGGER.info("Successfully sent message!");
            } else {
                LOGGER.error("could not sent message :(");
            }
        } catch (Exception e) {
            String msg = "could not send message, got error: " + e.getMessage();
            LOGGER.error(msg);
            //TODO: make this general internal server exception
            throw new BadRequestException(msg);
        }
    }

    public void populateResponse(Response response, ResponseDto dto) {
        response.setOfferPrice(dto.offerPrice);
        response.setExchangeLocation(dto.exchangeLocation);
        response.setExchangeTime(dto.exchangeTime);
        response.setReturnLocation(dto.returnLocation);
        response.setReturnTime(dto.returnTime);
        try {
            Response.PriceType priceType = Response.PriceType.valueOf(dto.priceType.toUpperCase());
            response.setPriceType(priceType);
        } catch (IllegalArgumentException e) {
            String msg = "Unable to set price type to [" + dto.priceType + "]. Options are FLAT, PER_HOUR, PER_DAY";
            LOGGER.error(msg);
            throw new com.impulsecontrol.lend.exception.IllegalArgumentException(msg);
        }
    }

    public void updateResponse(ResponseDto dto, Response response, Request request, String userId) {
        if (!(request.getStatus() == Request.Status.OPEN) &&
                !(request.getStatus() == Request.Status.FULFILLED && request.getFulfilledByUserId().equals(userId))) {
            String msg = "unable to update this response because the request is not longer open";
            LOGGER.error(msg);
            throw new UnauthorizedException(msg);
        }
        if (request.getExpireDate() != null && request.getExpireDate().before(new Date())) {
            request.setStatus(Request.Status.CLOSED);
            requestCollection.save(request);
            response.setBuyerStatus(Response.BuyerStatus.CLOSED);
            response.setResponseStatus(Response.Status.CLOSED);
            responseCollection.save(response);
            String msg = "Cannot update this offer because the request was recently closed.";
            LOGGER.info(msg);
            throw new BadRequestException(msg);
        }
        populateResponse(response, dto);
        if (request.getUser().getId().equals(userId)) {
            updateBuyerStatus(response, dto);
        } else {
            updateSellerStatus(response, dto, request);
        }
    }

    private void updateBuyerStatus(Response response, ResponseDto dto) {
        if (response.getBuyerStatus().toString().toLowerCase() != dto.buyerStatus.toLowerCase()) {
            String buyerStatus = dto.buyerStatus.toLowerCase();
            if (buyerStatus == Response.BuyerStatus.ACCEPTED.toString().toLowerCase()) {
                response.setBuyerStatus(Response.BuyerStatus.ACCEPTED);
                //TODO: send notification to seller
            } else if (buyerStatus == Response.BuyerStatus.DECLINED.toString().toLowerCase()) {
                response.setBuyerStatus(Response.BuyerStatus.DECLINED);
                response.setResponseStatus(Response.Status.CLOSED);
                //TODO: send notification to seller
            }
        }
    }

    private void updateSellerStatus(Response response, ResponseDto dto, Request request) {
        if (response.getSellerStatus().toString().toLowerCase() != dto.sellerStatus.toLowerCase()) {
            String sellerStatus = dto.sellerStatus.toLowerCase();
            if (sellerStatus == Response.SellerStatus.ACCEPTED.toString().toLowerCase()) {
                response.setSellerStatus(Response.SellerStatus.ACCEPTED);
                response.setResponseStatus(Response.Status.ACCEPTED);
                request.setStatus(Request.Status.FULFILLED);
                BasicDBObject query = new BasicDBObject();
                query.append("requestId", request.getId());
                DBCursor requestResponses = responseCollection.find(query).sort(new BasicDBObject("responseTime", -1));
                List<Response> responses = requestResponses.toArray();
                requestResponses.close();
                responses.forEach(r -> {
                    if (r.getId() != response.getId()) {
                        r.setBuyerStatus(Response.BuyerStatus.CLOSED);
                        r.setResponseStatus(Response.Status.CLOSED);
                        responseCollection.save(r);
                        //TODO: send notification to sellers that the request is closed
                    }
                });
            }
        }

    }
}
