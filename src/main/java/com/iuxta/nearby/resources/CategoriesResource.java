package com.iuxta.nearby.resources;

import com.codahale.metrics.annotation.Timed;
import com.iuxta.nearby.dto.CategoryDto;
import com.iuxta.nearby.exception.NotFoundException;
import com.iuxta.nearby.model.Category;
import com.iuxta.nearby.model.User;
import io.dropwizard.auth.Auth;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiImplicitParam;
import io.swagger.annotations.ApiImplicitParams;
import io.swagger.annotations.ApiParam;
import org.mongojack.DBCursor;
import org.mongojack.JacksonDBCollection;
import org.mongojack.WriteResult;

import javax.validation.Valid;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import java.util.List;

/**
 * Created by kerrk on 8/19/16.
 */
@Path("/categories")
@Api("/categories")
public class CategoriesResource {

    private JacksonDBCollection<Category, String> categoriesCollection;

    public CategoriesResource(JacksonDBCollection<Category, String> categoriesCollection) {
        this.categoriesCollection = categoriesCollection;
    }

    @GET
    @Produces(value = MediaType.APPLICATION_JSON)
    @Timed
    @ApiImplicitParams({ @ApiImplicitParam(name = "x-auth-token",
            value = "the authentication token received from facebook",
            dataType = "string",
            paramType = "header"),
            @ApiImplicitParam(name = "x-auth-method",
                    value = "the authentication method, either \"facebook\" (default if empty) or \"google\"",
                    dataType = "string",
                    paramType = "header") })
    public List<CategoryDto> getCategories(@Auth @ApiParam(hidden=true) User principal) {
        DBCursor categoriesRequests = categoriesCollection.find();
        List<Category> categories =  categoriesRequests.toArray();
        categoriesRequests.close();
        return CategoryDto.transform(categories);
    }

    @GET
    @Path("/{id}")
    @Produces(value = MediaType.APPLICATION_JSON)
    @Timed
    @ApiImplicitParams({ @ApiImplicitParam(name = "x-auth-token",
            value = "the authentication token received from facebook",
            dataType = "string",
            paramType = "header"),
            @ApiImplicitParam(name = "x-auth-method",
                    value = "the authentication method, either \"facebook\" (default if empty) or \"google\"",
                    dataType = "string",
                    paramType = "header") })
    public CategoryDto getCategory(@Auth @ApiParam(hidden=true) User principal, @PathParam("id") String id) {
        Category category = categoriesCollection.findOneById(id);
        if (category == null) {
            throw new NotFoundException("Request [" + id + "] was not found.");
        }
        return new CategoryDto(category);
    }

    /**
     * TODO: we should eventually remove this endpoint or lock this down so only certain users can create new categories
     * @param principal
     * @param category
     * @return
     */
    @POST
    @Consumes(value = MediaType.APPLICATION_JSON)
    @Produces(value = MediaType.APPLICATION_JSON)
    @Timed
    @ApiImplicitParams({ @ApiImplicitParam(name = "x-auth-token",
            value = "the authentication token received from facebook",
            dataType = "string",
            paramType = "header"),
            @ApiImplicitParam(name = "x-auth-method",
                    value = "the authentication method, either \"facebook\" (default if empty) or \"google\"",
                    dataType = "string",
                    paramType = "header") })
    public CategoryDto createCategory(@Auth @ApiParam(hidden=true) User principal, @Valid CategoryDto category) {
        Category cat = new Category();
        cat.setName(category.name);
        cat.setExamples(category.examples);
        WriteResult<Category, String> newCategory = categoriesCollection.insert(cat);
        cat = newCategory.getSavedObject();
        return new CategoryDto(cat);
    }


}
