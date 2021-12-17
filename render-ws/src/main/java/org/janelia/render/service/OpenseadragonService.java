package org.janelia.render.service;

import java.net.UnknownHostException;
import java.util.Collections;
import java.util.Map;
import java.util.Set;

import javax.ws.rs.BeanParam;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.FormParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.janelia.alignment.ImageAndMask;
import org.janelia.alignment.RenderParameters;
import org.janelia.alignment.spec.ChannelSpec;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.spec.TransformSpecMetaData;
import org.janelia.alignment.spec.stack.StackId;
import org.janelia.alignment.spec.stack.StackMetaData;
import org.janelia.render.service.dao.RenderDao;
import org.janelia.render.service.model.ObjectNotFoundException;
import org.janelia.render.service.model.RenderQueryParameters;
import org.janelia.render.service.util.RenderServiceUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiResponse;
import io.swagger.annotations.ApiResponses;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import java.net.InetAddress;
import java.net.UnknownHostException;

@Path("/")
@Api(tags = {"Openseadragon API's"})
public class OpenseadragonService {

    private final RenderDao renderDao;
    private final RenderDataService renderDataService;

    @SuppressWarnings({"UnusedDeclaration", "WeakerAccess"})
    public OpenseadragonService()
            throws UnknownHostException {
        this(RenderDao.build());
    }

    private OpenseadragonService(final RenderDao renderDao) {
        this.renderDao = renderDao;
        this.renderDataService = new RenderDataService(renderDao);
    }


    @Path("v1/openseadragon")
    @POST
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(
            value = "Get test service")
    @ApiResponses(value = {
            @ApiResponse(code = 404, message = "test service not found"),
    })
    public String getTestService(@FormParam("clustername") final String clustername,
                              @FormParam("username") final String username,
                              @FormParam("password") final String password,
                              @FormParam("StackOwner") final String stackowner,
                              @FormParam("StackProject") final String stackproject,
                              @FormParam("Stack") final String stack,
                              @FormParam("data_prep") final String data_prep,
                              @FormParam("data_prepsh") final String data_prepsh,
                              @FormParam("openseadragonDataDestinationFolder") final String openseadragonDataDestinationFolder,
                              @FormParam("openseadragonDataHost") final String openseadragonDataHost,
                              @FormParam("openseadragonDataSourceFolder") final String openseadragonDataSourceFolder,
                                 @FormParam("renderHost") final String renderHost) {
        String return_error = "";
        LOG.info("getTestService: entry, clustername={}, username={}, password={}",
                clustername, username, password);
        JSch jsch = new JSch();
        Session session;
        //String JSerror=" ";
        //String IOerror=" ";
        String line="";
        try {

            // Open a Session to remote SSH server and Connect.
            // Set User and IP of the remote host and SSH port.
            session = jsch.getSession(username, clustername, 22);
            // When we do SSH to a remote host for the 1st time or if key at the remote host
            // changes, we will be prompted to confirm the authenticity of remote host.
            // This check feature is controlled by StrictHostKeyChecking ssh parameter.
            // By default StrictHostKeyChecking  is set to yes as a security measure.
            session.setConfig("StrictHostKeyChecking", "no");
            //Set password
            session.setPassword(password);
            session.connect();

            // create the execution channel over the session
            ChannelExec channelExec = (ChannelExec) session.openChannel("exec");
            // Set the command to execute on the channel and execute the command
            channelExec.setCommand("bsub -P test -R 'rusage[mem=6000]' -q standard 'python3 "+data_prep+" "+stackowner+" "+stackproject+" "+stack+" "
                    +data_prep+" "+data_prepsh+" "+openseadragonDataDestinationFolder+" "+openseadragonDataHost+" "+openseadragonDataSourceFolder+
                    " "+renderHost+"'");
            channelExec.connect();

            // Get an InputStream from this channel and read messages, generated
            // by the executing command, from the remote side.
            InputStream in = channelExec.getInputStream();
            BufferedReader reader = new BufferedReader(new InputStreamReader(in));

            while ((line = reader.readLine()) != null) {
                System.out.println(line);
            }

            // Command execution completed here.

            // Retrieve the exit status of the executed command
            int exitStatus = channelExec.getExitStatus();
            if (exitStatus > 0) {
                System.out.println("Remote script exec error! " + exitStatus);
                return_error= "Remote script exec error! ";
            }
            //Disconnect the Session
            session.disconnect();
        } catch (JSchException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }


        //return clustername+" "+username+" "+password+" "+stackowner+" "+stackproject+" "+stack+ " "+return_error+" "+line;
        return line;
        //return host_name;
    }

    private static final Logger LOG = LoggerFactory.getLogger(OpenseadragonService.class);
}