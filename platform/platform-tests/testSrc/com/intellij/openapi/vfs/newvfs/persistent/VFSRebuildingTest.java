// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.vfs.newvfs.persistent;

import com.intellij.openapi.util.Ref;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vfs.newvfs.persistent.PersistentFSRecordsStorageFactory.RecordsStorageKind;
import com.intellij.platform.diagnostic.telemetry.TelemetryManager;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.testFramework.TemporaryDirectory;
import com.intellij.util.io.PageCacheUtils;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static com.intellij.openapi.vfs.newvfs.persistent.PersistentFSRecordsStorageFactory.RecordsStorageKind.*;
import static com.intellij.openapi.vfs.newvfs.persistent.VFSNeedsRebuildException.RebuildCause.IMPL_VERSION_MISMATCH;
import static com.intellij.openapi.vfs.newvfs.persistent.VFSNeedsRebuildException.RebuildCause.NOT_CLOSED_PROPERLY;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.*;

/**
 * Test VFS version management and VFS rebuild on implementation version change
 */
public class VFSRebuildingTest {

  @Rule
  public final TemporaryDirectory temporaryDirectory = new TemporaryDirectory();

  @Test
  public void connection_ReopenedWithSameVersion_HasDataFromPreviousTurn() throws IOException {
    final Path cachesDir = temporaryDirectory.createDir();
    final int version = 1;

    final PersistentFSConnection connection = PersistentFSConnector.tryInit(
      cachesDir,
      version,
      true,
      new Ref<>(),
      Collections.emptyList()
    );
    final PersistentFSRecordsStorage records = connection.getRecords();
    assertEquals(
      "connection.records.version == tryInit(version)",
      records.getVersion(),
      version
    );
    //create few dummy records -- so we could check them exist after reopen:
    createRecords(connection, 3);
    final int recordsCountBeforeClose = records.recordsCount();

    PersistentFSConnector.disconnect(connection);

    final PersistentFSConnection reopenedConnection = PersistentFSConnector.tryInit(
      cachesDir,
      version,
      true,
      new Ref<>(),
      Collections.emptyList()
    );

    assertEquals(
      "VFS should not be rebuild -- it should successfully load persisted version from disk",
      reopenedConnection.getRecords().recordsCount(),
      recordsCountBeforeClose
    );
  }

  @Test
  public void connection_ReopenedWithSameVersion_HasTimestampFromPreviousTurn() throws IOException, InterruptedException {
    final Path cachesDir = temporaryDirectory.createDir();
    final int version = 1;

    final PersistentFSConnection connection = PersistentFSConnector.tryInit(
      cachesDir,
      version,
      true,
      new Ref<>(),
      Collections.emptyList()
    );
    final PersistentFSRecordsStorage records = connection.getRecords();
    assertEquals(
      "connection.records.version == tryInit(version)",
      records.getVersion(),
      version
    );
    //create few dummy records
    createRecords(connection, 3);
    final long fsRecordsCreationTimestampBeforeDisconnect = records.getTimestamp();

    PersistentFSConnector.disconnect(connection);

    Thread.sleep(1000);

    final PersistentFSConnection reopenedConnection = PersistentFSConnector.tryInit(
      cachesDir,
      version,
      true,
      new Ref<>(),
      Collections.emptyList()
    );

    assertEquals(
      "VFS should NOT be rebuild -- reopened VFS should have creation timestamp of VFS before disconnect",
      reopenedConnection.getRecords().getTimestamp(),
      fsRecordsCreationTimestampBeforeDisconnect
    );
  }

  @Test
  public void connection_ReopenedWithDifferentVersion_Fails() throws IOException {
    final Path cachesDir = temporaryDirectory.createDir();
    final int version = 1;
    final PersistentFSConnection connection = PersistentFSConnector.tryInit(
      cachesDir,
      version,
      true,
      new Ref<>(),
      Collections.emptyList()
    );
    assertEquals(
      "connection.records.version == tryInit(version)",
      version,
      connection.getRecords().getVersion()
    );
    PersistentFSConnector.disconnect(connection);


    final int differentVersion = version + 1;
    try {
      PersistentFSConnector.tryInit(
        cachesDir,
        differentVersion,
        true,
        new Ref<>(),
        Collections.emptyList()
      );
      fail(
        "VFS opening must fail, since the supplied 'current' version is different from that was used to initialize on-disk structures before");
    }
    catch (VFSNeedsRebuildException e) {
      assertEquals(
        "rebuildCause must be IMPL_VERSION_MISMATCH",
        e.rebuildCause(),
        IMPL_VERSION_MISMATCH
      );
    }
  }

  @Test
  public void connection_corruptionMarkerFileIsCreatedOnAsk_AndContainCorruptionReasonAndCauseExceptionTrace() throws IOException {
    Path cachesDir = temporaryDirectory.createDir();

    final String corruptionReason = "VFS corrupted because I said so";
    final String corruptionCauseMessage = "Something happens here";

    final PersistentFSConnector.InitializationResult initializationResult = PersistentFSConnector.connect(
      cachesDir,
      /*version: */ 1,
      true,
      new Ref<>(),
      Collections.emptyList()
    );
    PersistentFSConnection connection = initializationResult.connection;
    final Path corruptionMarkerFile = connection.getPersistentFSPaths().getCorruptionMarkerFile();
    try {
      connection.scheduleVFSRebuild(
        corruptionReason,
        new Exception(corruptionCauseMessage)
      );
    }
    finally {
      PersistentFSConnector.disconnect(connection);
    }

    assertTrue(
      "Corruption marker file " + corruptionMarkerFile + " must be created",
      Files.exists(corruptionMarkerFile)
    );

    final String corruptingMarkerContent = Files.readString(corruptionMarkerFile, UTF_8);
    assertTrue(
      "Corruption file must contain corruption reason [" + corruptionReason + "]: " + corruptingMarkerContent,
      corruptingMarkerContent.contains(corruptionReason)
    );
    assertTrue(
      "Corruption file must contain corruption cause [" + corruptionCauseMessage + "]: " + corruptingMarkerContent,
      corruptingMarkerContent.contains(corruptionCauseMessage)
    );
  }

  //==== more top-level tests

  @Test
  public void VFS_isRebuilt_OnlyIf_ImplementationVersionChanged() throws Exception {
    //skip IN_MEMORY impl, since it is not really persistent
    //skip OVER_LOCK_FREE_FILE_CACHE impl if !LOCK_FREE_VFS_ENABLED (will fail)
    final List<RecordsStorageKind> allKinds = PageCacheUtils.LOCK_FREE_VFS_ENABLED ?
                                              List.of(REGULAR, OVER_LOCK_FREE_FILE_CACHE, OVER_MMAPPED_FILE) :
                                              List.of(REGULAR, OVER_MMAPPED_FILE);

    //check all combinations (from->to) of implementations:
    for (RecordsStorageKind kindBefore : allKinds) {
      for (RecordsStorageKind kindAfter : allKinds) {
        final Path cachesDir = temporaryDirectory.createDir();
        PersistentFSRecordsStorageFactory.setRecordsStorageImplementation(kindBefore);
        final FSRecordsImpl records = FSRecordsImpl.connect(cachesDir);

        final long firstVfsCreationTimestamp = records.getCreationTimestamp();

        records.dispose();
        Thread.sleep(500);//ensure system clock is moving

        //reopen:
        PersistentFSRecordsStorageFactory.setRecordsStorageImplementation(kindAfter);
        final FSRecordsImpl reopenedRecords = FSRecordsImpl.connect(cachesDir);
        final long reopenedVfsCreationTimestamp = reopenedRecords.getCreationTimestamp();


        if (kindBefore == kindAfter) {
          assertEquals(
            "VFS must _not_ be rebuild since storage version impl is not changed (" + kindBefore + " -> " + kindAfter + ")",
            firstVfsCreationTimestamp,
            reopenedVfsCreationTimestamp
          );
        }
        else {
          assertNotEquals(
            "VFS _must_ be rebuild from scratch since storage version impl is changed (" + kindBefore + " -> " + kindAfter + ")",
            firstVfsCreationTimestamp,
            reopenedVfsCreationTimestamp
          );
        }
      }
    }
  }


  @Test
  public void VFS_isRebuilt_If_AnyStorageFileRemoved() throws Exception {
    //skip IN_MEMORY impl, since it is not really persistent
    //skip OVER_LOCK_FREE_FILE_CACHE impl if !LOCK_FREE_VFS_ENABLED (will fail)
    final List<RecordsStorageKind> allKinds = PageCacheUtils.LOCK_FREE_VFS_ENABLED ?
                                              List.of(REGULAR, OVER_LOCK_FREE_FILE_CACHE, OVER_MMAPPED_FILE) :
                                              List.of(REGULAR, OVER_MMAPPED_FILE);

    List<String> filesNotLeadingToVFSRebuild = new ArrayList<>();
    for (RecordsStorageKind storageKind : allKinds) {
      int vfsFilesCount = 1;
      for (int i = 0; i < vfsFilesCount; i++) {
        final Path cachesDir = temporaryDirectory.createDir();
        PersistentFSRecordsStorageFactory.setRecordsStorageImplementation(storageKind);

        final FSRecordsImpl records = FSRecordsImpl.connect(cachesDir);
        final long firstVfsCreationTimestamp = records.getCreationTimestamp();

        //add something to VFS so it is not empty
        final int id = records.createRecord();
        records.setName(id, "test", PersistentFSRecordsStorage.NULL_ID);
        try (var stream = records.writeContent(id, false)) {
          stream.writeUTF("test");
        }

        records.dispose();
        Thread.sleep(500);//ensure system clock is moving

        final Path[] vfsFiles = Files.list(cachesDir)
          .filter(path -> Files.isRegularFile(path))
          //ResizableMappedFile is able to recover .len file
          .filter(path -> !path.getFileName().toString().endsWith(".len"))
          .sorted()
          .toArray(Path[]::new);
        vfsFilesCount = vfsFiles.length;
        final Path fileToDelete = vfsFiles[i];

        FileUtil.delete(fileToDelete);

        //reopen:
        PersistentFSRecordsStorageFactory.setRecordsStorageImplementation(storageKind);
        final FSRecordsImpl reopenedRecords = FSRecordsImpl.connect(cachesDir);
        final long reopenedVfsCreationTimestamp = reopenedRecords.getCreationTimestamp();
        if (reopenedVfsCreationTimestamp == firstVfsCreationTimestamp) {
          filesNotLeadingToVFSRebuild.add(fileToDelete.getFileName().toString());
        }
        else {
          Optional<Throwable> rebuildCauseException = reopenedRecords.initializationFailures().stream()
            .filter(ex -> ex instanceof VFSNeedsRebuildException)
            .findFirst();
          System.out.println(fileToDelete.getFileName().toString() + " removed -> " +
                             rebuildCauseException.map(ex -> ((VFSNeedsRebuildException)ex).rebuildCause()));
        }
      }

      //FIXME RC: currently 'attributes_enums.dat' is not checked during VFS init
      filesNotLeadingToVFSRebuild.remove("attributes_enums.dat");
      assertTrue(
        "VFS[" + storageKind + "] is not rebuilt if one of " + filesNotLeadingToVFSRebuild + " is deleted",
        filesNotLeadingToVFSRebuild.isEmpty()
      );
    }
  }


  @Test
  public void VFS_MustNOT_FailOnReopen_if_ExplicitlyDisconnected() throws IOException {
    final Path cachesDir = temporaryDirectory.createDir();
    final int version = 1;

    final PersistentFSConnection connection = PersistentFSConnector.tryInit(
      cachesDir,
      version,
      true,
      new Ref<>(),
      Collections.emptyList()
    );
    try {
      final PersistentFSRecordsStorage records = connection.getRecords();
      records.setConnectionStatus(PersistentFSHeaders.CONNECTED_MAGIC);
    }
    finally {
      //stamps connectionStatus=SAFELY_CLOSED_MAGIC
      connection.close();
    }

    final PersistentFSConnection reopenedConnection = PersistentFSConnector.tryInit(
      cachesDir,
      version,
      true,
      new Ref<>(),
      Collections.emptyList()
    );
    try {
      assertEquals("connectionStatus must be SAFELY_CLOSED since connection was disconnect()-ed",
                   PersistentFSHeaders.SAFELY_CLOSED_MAGIC,
                   reopenedConnection.getRecords().getConnectionStatus());
    }
    finally {
      PersistentFSConnector.disconnect(reopenedConnection);
    }
  }

  @Test
  public void VFS_Must_FailOnReopen_RequestingRebuild_if_NOT_ExplicitlyDisconnected() throws IOException {
    final Path cachesDir = temporaryDirectory.createDir();
    final int version = 1;

    final PersistentFSConnection connection = PersistentFSConnector.tryInit(
      cachesDir,
      version,
      true,
      new Ref<>(),
      Collections.emptyList()
    );
    connection.doForce();
    try {
      final PersistentFSRecordsStorage records = connection.getRecords();
      records.setConnectionStatus(PersistentFSHeaders.CONNECTED_MAGIC);
      records.force();

      //do NOT call connection.close() -- just reopen the connection:

      try {
        PersistentFSConnector.tryInit(
          cachesDir,
          version,
          true,
          new Ref<>(),
          Collections.emptyList()
        );
        fail("VFS init must fail with NOT_CLOSED_SAFELY");
      }
      catch (VFSNeedsRebuildException requestToRebuild) {
        //OK, this is what we expect:
        assertEquals(
          "rebuildCause must be NOT_CLOSED_PROPERLY",
          NOT_CLOSED_PROPERLY,
          requestToRebuild.rebuildCause()
        );
      }
    }
    finally {
      connection.close();
    }
  }


  @Test
  public void benchmarkVfsInitializationTime() throws Exception {
    PlatformTestUtil.startPerformanceTest(
        "create VFS from scratch", 100,
        () -> {
          Path cachesDir = temporaryDirectory.createDir();
          int version = 1;

          PersistentFSConnector.connect(
            cachesDir,
            version,
            true,
            new Ref<>(),
            Collections.emptyList()
          );
          //PersistentFSConnector.disconnect(initResult.connection);
          //System.out.println(initResult.totalInitializationDurationNs / 1000_000);
        })
      .ioBound()
      .warmupIterations(1)
      .attempts(4)
      .assertTiming();
  }

  //==== infrastructure:

  @After
  public void tearDown() throws Exception {
    PersistentFSRecordsStorageFactory.resetRecordsStorageImplementation();
  }

  private static void createRecords(final PersistentFSConnection connection,
                                    final int nRecords) throws IOException {
    final PersistentFSRecordsStorage records = connection.getRecords();
    for (int i = 0; i < nRecords; i++) {
      //Why .cleanRecord(): because PersistentFSSynchronizedRecordsStorage does not persist allocated
      // record if allocated record fields weren't modified. This is, generally, against
      // PersistentFSRecordsStorage contract, but (it seems) no use-sites are affected, and I
      // decided to not fix it, since it could affect performance for legacy implementation -- for nothing.
      //
      records.cleanRecord(records.allocateRecord());
    }
    connection.markDirty();
  }
}
