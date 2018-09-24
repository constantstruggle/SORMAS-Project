package de.symeda.sormas.app.rest;

import java.util.List;

import de.symeda.sormas.api.region.CommunityDto;
import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.GET;
import retrofit2.http.POST;
import retrofit2.http.Path;
import retrofit2.http.Query;

/**
 * Created by Martin Wahnschaffe on 07.06.2016.
 */
public interface CommunityFacadeRetro {

    @GET("communities/all/{since}")
    Call<List<CommunityDto>> pullAllSince(@Path("since") long since);

    @POST("communities/query")
    Call<List<CommunityDto>> pullByUuids(@Body List<String> uuids);

    @GET("communities/uuids")
    Call<List<String>> pullUuids();
}
