package com.continuuity.common.zookeeper;

import java.io.IOException;

import org.junit.After;
import org.junit.Before;

import com.continuuity.common.utils.OSDetector;

/**
 *
 */
public class InMemoryZKBaseTest {
  protected InMemoryZookeeper server;

  @Before
  public void setupServer() throws Exception {
    server = new InMemoryZookeeper();
  }

  @After
  public void tearDownServer() throws Exception {
    try {
      server.close();
    } catch (IOException ioe) {
      // Windows fails here so going to ignore
      if (ioe.getMessage().startsWith("Failed to delete") &&
          OSDetector.isWindows()) {
        // do nothing
      } else throw ioe;
    }
  }
}

