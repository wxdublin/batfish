package org.batfish.coordinator;

import java.util.Arrays;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;

import org.codehaus.jettison.json.JSONArray;

@Path("/batfishcoordinator")
public class Service {

   @GET
   @Produces(MediaType.APPLICATION_JSON)
   public JSONArray getInfo() {
      return new JSONArray(
            Arrays.asList(
                  "",
                  "Batfish coordinator: enter ../application.wadl (relative to your URL) to see supported methods"));
   }

   @GET
   @Path("getstatus")
   @Produces(MediaType.APPLICATION_JSON)
   public JSONArray getStatus() {
      return new JSONArray(Arrays.asList("", Main.getCoordinator().getWorkStatus()));
   }
}