package com.continuuity.gateway.tools;

import com.continuuity.common.conf.CConfiguration;
import com.continuuity.common.conf.Constants;
import com.continuuity.data2.OperationException;
import com.continuuity.gateway.GatewayFastTestsSuite;
import org.junit.Assert;
import org.junit.Ignore;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;

/**
 * Tests the stream client.
 */
public class StreamClientTest {
  private static final String hostname = "127.0.0.1";
  private static final String AUTH_KEY = GatewayFastTestsSuite.getAuthHeader().getValue();

  private static final Logger LOG = LoggerFactory.getLogger(StreamClientTest.class);

  /**
   * This tests the StreamClient command line tool for various combinations of
   * command line arguments. Note that this tool is a command line tool,
   * and it prints stuff on the console. That is not testable with this
   * unit test. Therefore we only test whether it succeeds or fails for
   * certain command line argument.
   *
   * @throws Exception if anything goes wrong
   */
  @Test
  public void testUsage() throws Exception {
    CConfiguration configuration = CConfiguration.create();
    String port = Integer.toString(GatewayFastTestsSuite.getPort());

    // argument combinations that should return success
    String[][] goodArgsList = {
        { "--help" }, // print help
        { "create", "--stream", "teststream", "--host", hostname, "--port", port, "--apikey", AUTH_KEY }
    };

    // argument combinations that should lead to failure
    String[][] badArgsList = {
        { },
        { "create", "firststre@m", "--apikey", AUTH_KEY }, // create stream with illegal name
        { "fetch", "--key", "--apikey", AUTH_KEY }, // no key
    };

    // test each good combination
    for (String[] args : goodArgsList) {
      LOG.info("Testing: " + Arrays.toString(args));
      Assert.assertNotNull(new StreamClient().disallowSSL().execute(args, configuration));
    }
    // test each bad combination
    for (String[] args : badArgsList) {
      LOG.info("Testing: " + Arrays.toString(args));
      Assert.assertNull(new StreamClient().disallowSSL().execute(args, configuration));
    }
  }

  @Test
  public void testStreamFetch() throws OperationException {
    String streamId = "fetchstream";
    Assert.assertEquals("OK.", command(streamId, new String[] {
      "create"}));
    Assert.assertEquals("OK.", command(streamId, new String[] {
      "info"}));

    String groupId = command(streamId, new String[] {"group"});
    Assert.assertNotNull(groupId);

    Assert.assertEquals("", command(streamId, new String[]{
      "fetch", "--group", groupId}));

    Assert.assertEquals("OK.", command(streamId, new String[] {
      "send", "--body", "body1", "--header", "hname", "hvalue"}));

    Assert.assertEquals("body1", command(streamId, new String[] {
      "fetch", "--group", groupId}));
  }

  @Test
  public void testStreamView() throws OperationException {
    String streamId = "viewstream";
    Assert.assertEquals("OK.", command(streamId, new String[] {
      "create"}));
    Assert.assertEquals("OK.", command(streamId, new String[] {
      "info"}));

    Assert.assertEquals("0 events.", command(streamId, new String[]{
      "view"}));

    Assert.assertEquals("OK.", command(streamId, new String[]{
      "send", "--body", "body1", "--header", "hname", "hvalue"}));

    Assert.assertEquals("1 events.", command(streamId, new String[] {
      "view"}));

  }

  // TODO: (REACTOR-87) Temporarily disable.
  @Ignore
  @Test
  public void testStreamTruncate() throws OperationException {
    String streamId = "truncate-stream";
    Assert.assertEquals("OK.", command(streamId, new String[] {
      "create"}));
    Assert.assertEquals("OK.", command(streamId, new String[] {
      "info"}));

    Assert.assertEquals("0 events.", command(streamId, new String[]{
      "view"}));

    Assert.assertEquals("OK.", command(streamId, new String[]{
      "send", "--body", "body1", "--header", "hname", "hvalue"}));

    Assert.assertEquals("OK.", command(streamId, new String[]{
      "send", "--body", "body2", "--header", "hname", "hvalue"}));

    Assert.assertEquals("OK.", command(streamId, new String[] {
      "truncate"}));

    Assert.assertEquals("0 events.", command(streamId, new String[] {
      "view"}));

  }

  private String command(String streamId, String[] args) {
    CConfiguration configuration = CConfiguration.create();
    configuration.set(Constants.Router.ADDRESS, hostname);
    configuration.set(Constants.Router.FORWARD,
                      GatewayFastTestsSuite.getPort() + ":" + Constants.Service.GATEWAY + ",20000:$HOST");

    if (streamId != null) {
      args = Arrays.copyOf(args, args.length + 4);
      args[args.length - 4] = "--apikey";
      args[args.length - 3] = AUTH_KEY;
      args[args.length - 2] = "--stream";
      args[args.length - 1] = streamId;
    }
    return new StreamClient().disallowSSL().execute(args, configuration);
  }

}
