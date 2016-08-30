package com.impulsecontrol.lend.resources;

import com.codahale.metrics.annotation.Timed;
import com.impulsecontrol.lend.dto.RequestDto;
import com.impulsecontrol.lend.dto.UserDto;
import com.impulsecontrol.lend.exception.UnauthorizedException;
import com.impulsecontrol.lend.model.Request;
import com.impulsecontrol.lend.model.User;
import com.impulsecontrol.lend.service.UserService;
import com.mongodb.BasicDBObject;
import com.mongodb.DBObject;
import io.dropwizard.auth.Auth;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiImplicitParam;
import io.swagger.annotations.ApiImplicitParams;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import org.mongojack.DBCursor;
import org.mongojack.JacksonDBCollection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.validation.Valid;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.net.URI;
import java.util.List;


@Path("/users")
@Api("/users")
public class UserResource {

    private static final Logger LOGGER = LoggerFactory.getLogger(UserResource.class);

    private JacksonDBCollection<User, String> userCollection;
    private JacksonDBCollection<Request, String> requestCollection;
    private UserService userService;


    public UserResource(JacksonDBCollection<User, String> userCollection,
                        JacksonDBCollection<Request, String> requestCollection, UserService userService) {
        this.userCollection = userCollection;
        this.requestCollection = requestCollection;
        this.userService = userService;
    }

    @GET
    @Produces(value = MediaType.APPLICATION_JSON)
    @Path("/{id}")
    @Timed
    @ApiOperation(
            value = "get user",
            notes = "Limited information will be returned for users other than the currently authenticated user. " +
                    "In the future maybe we can allow users to configure which properties are public."
    )
    @ApiImplicitParams({
            @ApiImplicitParam(name = "x-auth-token",
            value = "the authentication token received from facebook",
            dataType = "string",
            paramType = "header")
    })
    public UserDto getUser(@Auth @ApiParam(hidden=true) User principal, @PathParam("id")
    @ApiParam(value = "the id of the user you wish to fetch info about, can use \"me\" to get the " +
            "current user's info") String id) {
        if (id.equals("me") || principal.getId().equals(id)) {
            return UserDto.getMyUserDto(principal);
        }
        User user = userCollection.findOneById(id);
        return UserDto.getOtherUserDto(user);
    }

    @POST
    @ApiOperation(
            value = "creates a new user with the details provided",
            notes = "no fields are required"
    )
    @ApiImplicitParams({
            @ApiImplicitParam(name = "x-auth-token",
                    value = "the authentication token received from facebook",
                    dataType = "string",
                    paramType = "header")
    })
    @Timed
    public Response createUser(@Auth @ApiParam(hidden=true) User principal, @Valid UserDto userDto) {
        if (userDto.userId != null && !userDto.userId.equals(principal.getUserId())) {
            String msg = "userId did not match the userId of the currently authenticated user";
            LOGGER.error(msg);
            throw new UnauthorizedException(msg);
        } else if (userDto.id != null && !userDto.id.equals(principal.getId())) {
            String msg = "id did not match the id of the currently authenticated user";
            LOGGER.error(msg);
            throw new UnauthorizedException(msg);
        }
        principal = userService.updateUser(principal, userDto);
        userCollection.save(principal);
        URI uriOfCreatedResource = URI.create("/user");
        return Response.created(uriOfCreatedResource).build();
    }

    @PUT
    @Path("/{id}")
    @ApiOperation(
            value = "Updates the user's information. Can only update the currenlty authenticated user.",
            notes = "We will never update the id of the user or the userId since the id is automatically generated " +
                    "by the database and the userId is generated by facebook"
    )
    @ApiImplicitParams({
            @ApiImplicitParam(name = "x-auth-token",
                    value = "the authentication token received from facebook",
                    dataType = "string",
                    paramType = "header")
    })
    @Timed
    public Response updateUser(@Auth @ApiParam(hidden=true) User principal, @Valid UserDto userDto,
                               @PathParam("id") @ApiParam(value = "id of the user to update, which must the" +
                                       " currently authenticated user's id or \"me\"") String id) {
        if (!id.equals("me") && !userDto.id.equals(principal.getId())) {
            String msg = "id did not match the id of the currently authenticated user";
            LOGGER.error(msg);
            throw new UnauthorizedException(msg);
        }
        principal = userService.updateUser(principal, userDto);
        userCollection.save(principal);

        // update all of the requests that the user made
        DBObject searchByUser = new BasicDBObject("user.userId", principal.getUserId());
        DBCursor userRequests = requestCollection.find(searchByUser).sort(new BasicDBObject("postDate", -1));
        List<Request> requests = userRequests.toArray();
        final User updatedUser = principal;
        requests.forEach(r -> {
            r.setUser(updatedUser);
            requestCollection.save(r);
        });
        userRequests.close();
        URI uriOfCreatedResource = URI.create("/user/" + id);
        return Response.created(uriOfCreatedResource).build();
    }

    /*@DELETE
    @Path("/{id}")
    @Timed
    public Response deleteUser(@PathParam("id") String id) {
    }*/

    @GET
    @Produces(value = MediaType.APPLICATION_JSON)
    @Path("/{id}/requests")
    @Timed
    @ApiOperation(
            value = "get a user's requests",
            notes = "right now we only allow a user to get their own requests, but perhaps we can allow users to " +
                    "make their request history public"
    )
    @ApiImplicitParams({@ApiImplicitParam(name = "x-auth-token",
            value = "the authentication token received from facebook",
            dataType = "string",
            paramType = "header")
    })
    public List<RequestDto> getAllUserRequests(@Auth @ApiParam(hidden=true) User principal, @PathParam("id")
    @ApiParam( value = "the id of the user to get requests from, can use \"me\" to get the current user's info")
    String id) {
        if (!principal.getUserId().equals(id) && !id.equals("me")) {
            String msg = "User [" + principal.getUserId() +
                    "] is not authorized to get requests from user [" + id + "].";
            LOGGER.error(msg);
            throw new UnauthorizedException(msg);
        }
        DBObject searchByUser = new BasicDBObject("user.userId", principal.getUserId());
        DBCursor userRequests = requestCollection.find(searchByUser).sort(new BasicDBObject("postDate", -1));
        List<Request> requests = userRequests.toArray();
        userRequests.close();
        return RequestDto.transform(requests);
    }
}
