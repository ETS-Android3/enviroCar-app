/**
 * Copyright (C) 2013 - 2021 the enviroCar community
 *
 * This file is part of the enviroCar app.
 *
 * The enviroCar app is free software: you can redistribute it and/or
 * modify it under the terms of the GNU General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * The enviroCar app is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General
 * Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with the enviroCar app. If not, see http://www.gnu.org/licenses/.
 */
package org.envirocar.app.handler;

import android.content.Context;

import org.envirocar.core.entity.Track;
import org.envirocar.core.exception.DataRetrievalFailureException;
import org.envirocar.core.exception.DataUpdateFailureException;
import org.envirocar.core.exception.NotConnectedException;
import org.envirocar.core.exception.TrackSerializationException;
import org.envirocar.core.exception.UnauthorizedException;
import org.envirocar.core.injection.InjectApplicationScope;
import org.envirocar.core.logging.Logger;
import org.envirocar.core.util.TrackMetadata;
import org.envirocar.core.util.Util;
import org.envirocar.core.EnviroCarDB;

import java.util.ArrayList;

import javax.inject.Inject;
import javax.inject.Singleton;

import io.reactivex.Observable;
import io.reactivex.schedulers.Schedulers;

/**
 * TODO JavaDoc
 *
 * @author dewall
 */
@Singleton
public class TrackDAOHandler {
    private static final Logger LOGGER = Logger.getLogger(TrackDAOHandler.class);

    private final Context context;
    private final EnviroCarDB enviroCarDB;
    private final DAOProvider daoProvider;

    @Inject
    public TrackDAOHandler(@InjectApplicationScope Context context,
                           DAOProvider daoProvider, EnviroCarDB enviroCarDB) {
        this.context = context;
        this.enviroCarDB = enviroCarDB;
        this.daoProvider = daoProvider;
    }

    public Observable<Track> deleteLocalTrackObservable(Track track) {
        return enviroCarDB.deleteTrackObservable(track);
    }

    /**
     * Deletes a track and returns true if the track has been successfully deleted.
     *
     * @param trackID the id of the track to delete.
     * @return true if the track has been successfully deleted.
     */
    public boolean deleteLocalTrack(Track.TrackId trackID) {
        return deleteLocalTrack(
                enviroCarDB.getTrack(trackID)
                        .subscribeOn(Schedulers.io())
                        .blockingFirst());
    }

    /**
     * Deletes a track and returns true if the track has been successfully deleted.
     *
     * @param trackRef the reference of the track.
     * @return true if the track has been successfully deleted.
     */
    public boolean deleteLocalTrack(Track trackRef) {
        LOGGER.info(String.format("deleteLocalTrack(id = %s)", trackRef.getTrackID().getId()));

        // Only delete the track if the track is a local track.
        if (trackRef.isLocalTrack()) {
            LOGGER.info("deleteLocalTrack(...): Track is a local track.");
            enviroCarDB.deleteTrack(trackRef);
            return true;
        }

        LOGGER.warn("deleteLocalTrack(...): track is no local track. No deletion.");
        return false;
    }

    /**
     * Invokes the deletion of a remote track. Once the remote track has been successfully
     * deleted, this method also deletes the locally stored reference of that track.
     *
     * @param trackRef
     * @return
     * @throws UnauthorizedException
     * @throws NotConnectedException
     */
    public boolean deleteRemoteTrack(Track trackRef) throws UnauthorizedException,
            NotConnectedException {
        LOGGER.info(String.format("deleteRemoteTrack(id = %s)", trackRef.getTrackID().getId()));

        // Check whether this track is a remote track.
        if (!trackRef.isRemoteTrack()) {
            LOGGER.warn("Track reference to upload is no remote track.");
            return false;
        }

        // Delete the track first remote and then the local reference.
        try {
            daoProvider.getTrackDAO().deleteTrack(trackRef);
        } catch (DataUpdateFailureException e) {
            e.printStackTrace();
        }

        enviroCarDB.deleteTrack(trackRef);

        // Successfully deleted the remote track.
        LOGGER.info("deleteRemoteTrack(): Successfully deleted the remote track.");
        return true;
    }

    public boolean deleteAllRemoteTracksLocally() {
        LOGGER.info("deleteAllRemoteTracksLocally()");
        enviroCarDB.deleteAllRemoteTracks()
                .subscribeOn(Schedulers.io())
                .blockingFirst();
        return true;
    }

    public Observable<Integer> getLocalTrackCount() {
        return enviroCarDB.getAllLocalTracks(true)
                .map(tracks -> tracks.size());
    }

    public Observable<TrackMetadata> updateTrackMetadataObservable(Track track, String touVersion) {
        return Observable.just(track)
                .map(track1 -> new TrackMetadata(Util.getVersionString(context), touVersion))
                .flatMap(trackMetadata -> updateTrackMetadata(track
                                .getTrackID(),
                        trackMetadata));
    }

    public Observable<TrackMetadata> updateTrackMetadata(
            Track.TrackId trackId, TrackMetadata trackMetadata) {
        return enviroCarDB.getTrack(trackId, true)
                .map(track -> {
                    TrackMetadata result = track.updateMetadata(trackMetadata);
                    enviroCarDB.updateTrack(track);
                    return result;
                });
    }

    public Observable<Track> fetchRemoteTrackObservable(Track remoteTrack) {
        return Observable.create(emitter -> {
            try {
                emitter.onNext(fetchRemoteTrack(remoteTrack));
                emitter.onComplete();
            } catch (NotConnectedException | DataRetrievalFailureException | UnauthorizedException e) {
                emitter.onError(e);
            }
        });
    }

    public Track fetchRemoteTrack(Track remoteTrack) throws NotConnectedException,
            UnauthorizedException, DataRetrievalFailureException {
        Track downloadedTrack = daoProvider.getTrackDAO().getTrackById(remoteTrack
                .getRemoteID());

        // Deep copy... TODO improve this.
        remoteTrack.setName(downloadedTrack.getName());
        remoteTrack.setDescription(downloadedTrack.getDescription());
        remoteTrack.setMeasurements(new ArrayList<>(downloadedTrack.getMeasurements()));
        remoteTrack.setCar(downloadedTrack.getCar());
        remoteTrack.setTrackStatus(downloadedTrack.getTrackStatus());
        remoteTrack.setMetadata(downloadedTrack.getMetadata());

        remoteTrack.setStartTime(downloadedTrack.getStartTime());
        remoteTrack.setEndTime(downloadedTrack.getEndTime());
        remoteTrack.setDownloadState(Track.DownloadState.DOWNLOADED);

        try {
            enviroCarDB.insertTrack(remoteTrack);
        } catch (TrackSerializationException e) {
            LOGGER.error(e.getMessage(), e);
        }
        //        mDBAdapter.insertTrack(remoteTrack, true);
        return remoteTrack;
    }
}
