/**
 *    Retz
 *    Copyright (C) 2016 Nautilus Technologies, Inc.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package io.github.retz.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import io.github.retz.auth.AuthHeader;
import io.github.retz.cli.TimestampHelper;
import io.github.retz.db.Database;
import io.github.retz.mesosc.MesosHTTPFetcher;
import io.github.retz.misc.Pair;
import io.github.retz.protocol.*;
import io.github.retz.protocol.data.Application;
import io.github.retz.protocol.data.DirEntry;
import io.github.retz.protocol.data.FileContent;
import io.github.retz.protocol.data.Job;
import io.github.retz.scheduler.*;
import org.apache.mesos.Protos;
import org.apache.mesos.SchedulerDriver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import spark.Request;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;

import static io.github.retz.web.WebConsole.validateOwner;
import static spark.Spark.halt;

public class JobRequestHandler {
    private static final Logger LOG = LoggerFactory.getLogger(JobRequestHandler.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static Optional<RetzScheduler> scheduler = Optional.empty();
    private static Optional<SchedulerDriver> driver = Optional.empty();

    static {
        MAPPER.registerModule(new Jdk8Module());
    }

    static void setScheduler(RetzScheduler sched) {
        scheduler = Optional.ofNullable(sched);
    }

    static void setDriver(SchedulerDriver d) {
        driver = Optional.ofNullable(d);
    }

    static String listJob(spark.Request req, spark.Response res) throws JsonProcessingException {
        Optional<AuthHeader> authHeaderValue = WebConsole.getAuthInfo(req);
        LOG.debug("list jobs owned by {}", authHeaderValue.get().key());
        ListJobResponse listJobResponse = list(authHeaderValue.get().key(), -1);
        listJobResponse.ok();
        res.status(200);
        res.type("application/json");
        return MAPPER.writeValueAsString(listJobResponse);
    }

    private static Optional<Job> getJobAndVerify(Request req) throws IOException {
        int id = Integer.parseInt(req.params(":id"));
        Optional<AuthHeader> authHeaderValue = WebConsole.getAuthInfo(req);

        if (!authHeaderValue.isPresent()) {
            LOG.debug("Authorization header lacking?");
            return Optional.empty();
        }
        LOG.debug("get-xxx id={}, user={}", id, authHeaderValue.get().key());

        Optional<AppJobPair> maybePair = Database.getInstance().getAppJob(id);
        if (maybePair.isPresent()) {
            AppJobPair pair = maybePair.get();
            if (pair.application().getOwner().equals(authHeaderValue.get().key())) {
                return Optional.of(pair.job());
            }
        }
        return Optional.empty();
    }

    static String getJob(spark.Request req, spark.Response res) throws IOException {
        Optional<Job> maybeJob = getJobAndVerify(req);

        res.type("application/json");

        Response response = new GetJobResponse(maybeJob);
        response.status("ok");
        res.status(200);
        return MAPPER.writeValueAsString(response);
    }

    static String getFile(spark.Request req, spark.Response res) throws IOException {
        Optional<Job> job = getJobAndVerify(req);

        String file = req.queryParams("path");
        long offset = Long.parseLong(req.queryParams("offset"));
        long length = Long.parseLong(req.queryParams("length"));

        LOG.debug("get-file: path={}, offset={}, length={}", file, offset, length);
        res.type("application/json");

        Optional<FileContent> fileContent;
        if (job.isPresent() && job.get().url() != null // If url() is null, the job hasn't yet been started at Mesos
                && MesosHTTPFetcher.statHTTPFile(job.get().url(), file)) {
            Pair<Integer, String> payload = MesosHTTPFetcher.fetchHTTPFile(job.get().url(), file, offset, length);
            LOG.debug("Payload length={}, offset={}", payload.right().length(), offset);
            // TODO: what the heck happens when a file is not UTF-8 encodable???? How Mesos works?
            if (payload.left() == 200) {
                fileContent = Optional.ofNullable(MAPPER.readValue(payload.right(), FileContent.class));
            } else {
                return MAPPER.writeValueAsString(new ErrorResponse(payload.right()));
            }
        } else {
            fileContent = Optional.empty();
        }
        GetFileResponse getFileResponse = new GetFileResponse(job, fileContent);
        getFileResponse.ok();
        res.status(200);

        return MAPPER.writeValueAsString(getFileResponse);
    }

    static String getDir(spark.Request req, spark.Response res) throws JsonProcessingException {
        Optional<Job> job;
        try {
            job = getJobAndVerify(req);
        } catch (IOException e) {
            return MAPPER.writeValueAsString(new ErrorResponse(e.toString()));
        }

        String path = req.queryParams("path");
        LOG.debug("get-path: path={}", path);
        res.type("application/json");

        // Translating default as SparkJava's router doesn't route '.' or empty string
        if (ListFilesRequest.DEFAULT_SANDBOX_PATH.equals(path)) {
            path = "";
        }

        List ret;
        if (job.isPresent() && job.get().url() != null) {
            try {
                Pair<Integer,String> maybeJson = MesosHTTPFetcher.fetchHTTPDir(job.get().url(), path);
                if (maybeJson.left() == 200) {
                    ret = MAPPER.readValue(maybeJson.right(), new TypeReference<List<DirEntry>>() {
                    });
                } else {
                    return MAPPER.writeValueAsString(new ErrorResponse(path + ":" + maybeJson.left() + " " + maybeJson.right()));
                }
            } catch (FileNotFoundException e) {
                res.status(404);
                LOG.warn("path {} not found", path);
                return MAPPER.writeValueAsString(new ErrorResponse(path + " not found"));
            } catch (IOException e) {
                return MAPPER.writeValueAsString(new ErrorResponse(e.toString()));
            }
        } else {
            ret = Arrays.asList();
        }

        ListFilesResponse listFilesResponse = new ListFilesResponse(job, ret);
        listFilesResponse.status("ok");
        return MAPPER.writeValueAsString(listFilesResponse);
    }

    private static ListJobResponse list(String id, int limit) {
        List<Job> queue = new LinkedList<>(); //JobQueue.getAll();
        List<Job> running = new LinkedList<>();
        List<Job> finished = new LinkedList<>();

        for (Job job : JobQueue.getAll(id)) {
            switch (job.state()) {
                case QUEUED:
                    queue.add(job);
                    break;
                case STARTING:
                case STARTED:
                    running.add(job);
                    break;
                case FINISHED:
                case KILLED:
                    finished.add(job);
                    break;
                default:
                    LOG.error("Cannot be here: id={}, state={}", job.id(), job.state());
            }
        }
        return new ListJobResponse(queue, running, finished);
    }

    static String kill(Request req, spark.Response res) throws JsonProcessingException {
        LOG.debug("kill", req.params(":id"));
        int id = Integer.parseInt(req.params(":id")); // or 400 when failed?
        kill(id);
        res.status(200);
        KillResponse response = new KillResponse();
        response.ok();
        return MAPPER.writeValueAsString(response);
    }

    private static boolean kill(int id) {
        if (!driver.isPresent()) {
            LOG.error("Driver is not present; this setup should be wrong");
            return false;
        }

        Optional<Boolean> result = Stanchion.call(() -> {
            // TODO: non-application owner is even possible to kill job
            Optional<String> maybeTaskId = JobQueue.cancel(id, "Canceled by user");

            // There's a slight pitfall between cancel above and kill below where
            // no kill may be sent, RetzScheduler is exactly in resourceOffers and being scheduled.
            // Then this protocol returns false for sure.
            if (maybeTaskId.isPresent()) {
                Protos.TaskID taskId = Protos.TaskID.newBuilder().setValue(maybeTaskId.get()).build();
                Protos.Status status = driver.get().killTask(taskId);
                LOG.info("Job id={} was running and killed.");
                return status == Protos.Status.DRIVER_RUNNING;
            }
            return false;
        });

        if (result.isPresent()) {
            return result.get();
        } else {
            return false;
        }
    }

    static String schedule(spark.Request req, spark.Response res) throws IOException, InterruptedException {
        ScheduleRequest scheduleRequest = MAPPER.readValue(req.bodyAsBytes(), ScheduleRequest.class);
        res.type("application/json");
        Optional<Application> maybeApp = Applications.get(scheduleRequest.job().appid()); // TODO check owner right here
        if (!maybeApp.isPresent()) {
            // TODO: this warn log cannot be written in real stable release
            LOG.warn("No such application loaded: {}", scheduleRequest.job().appid());
            ErrorResponse response = new ErrorResponse("No such application: " + scheduleRequest.job().appid());
            res.status(404);
            return MAPPER.writeValueAsString(response);

        } else if (maybeApp.get().enabled()) {

            validateOwner(req, maybeApp.get());

            Job job = scheduleRequest.job();
            if (scheduler.isPresent()) {
                if (!scheduler.get().validateJob(job)) {
                    String msg = "Job " + job.toString() + " does not fit system limit " + scheduler.get().maxJobSize();
                    // TODO: this warn log cannot be written in real stable release
                    LOG.warn(msg);
                    halt(400, msg);
                }
            }

            job.schedule(JobQueue.issueJobId(), TimestampHelper.now());

            JobQueue.push(job);
            if (scheduler.isPresent() && driver.isPresent()) {
                LOG.info("Trying invocation from offer stock: {}", job);
                scheduler.get().maybeInvokeNow(driver.get(), job);

            }

            ScheduleResponse scheduleResponse = new ScheduleResponse(job);
            scheduleResponse.ok();
            LOG.info("Job '{}' at {} has been scheduled at {}.", job.cmd(), job.appid(), job.scheduled());

            res.status(201);
            return MAPPER.writeValueAsString(scheduleResponse);

        } else {
            // Application is currently disabled
            res.status(401);
            ErrorResponse response = new ErrorResponse("Application " + maybeApp.get().getAppid() + " is disabled");
            return MAPPER.writeValueAsString(response);
        }
    }
}
