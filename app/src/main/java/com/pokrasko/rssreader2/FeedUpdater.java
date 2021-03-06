package com.pokrasko.rssreader2;

import android.app.IntentService;
import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.net.Uri;
import android.os.Bundle;
import android.os.ResultReceiver;

import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.DefaultHandler;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParserFactory;

/**
 * Created by pokrasko on 10.11.14.
 */
public class FeedUpdater extends IntentService {
    private long feedId;
    private String feedTitle;
    private String feedDescription;
    private ResultReceiver receiver;
    private Uri feedUri;

    public static boolean running = false;

    public FeedUpdater() {
        super("FeedUpdater");
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        String feedUrl;

        running = true;

        feedId = intent.getLongExtra("feed_id", -1);
        feedDescription = intent.getStringExtra("description");
        feedTitle = intent.getStringExtra("title");

        receiver = intent.getParcelableExtra("receiver");

        if (feedId == -1) {
            feedUrl = intent.getStringExtra("url");
            String escapedFeedUrl = DatabaseUtils.sqlEscapeString(feedUrl);
            Cursor cursor = getContentResolver().query(
                    FeedContentProvider.CONTENT_FEEDS_URI,
                    null,
                    "url=" + escapedFeedUrl,
                    null,
                    null
            );
            if (cursor.getCount() != 0) {
                receiver.send(FeedResultReceiver.FEED_EXISTS_ERROR, Bundle.EMPTY);
                return;
            }

            ContentValues values = new ContentValues();
            values.put("title", feedUrl);
            values.put("description", "");
            values.put("url", feedUrl);

            feedTitle = feedUrl;
            feedUri = getContentResolver().insert(FeedContentProvider.CONTENT_FEEDS_URI, values);
            feedId = Long.parseLong(feedUri.getLastPathSegment());
        } else {
            feedUri = Uri.withAppendedPath(FeedContentProvider.CONTENT_FEEDS_URI, "" + feedId);
            Cursor cursor = getContentResolver().query(feedUri, new String[]{"title", "description", "url"}, null, null, null);
            cursor.moveToFirst();
            feedTitle = cursor.getString(cursor.getColumnIndexOrThrow("title"));
            feedDescription = cursor.getString(cursor.getColumnIndexOrThrow("description"));
            feedUrl = cursor.getString(cursor.getColumnIndexOrThrow("url"));
            cursor.close();
        }

        try {
            XMLReader reader = SAXParserFactory.newInstance().newSAXParser().getXMLReader();
            RSSHandler handler = new RSSHandler();
            reader.setContentHandler(handler);
            InputStream stream = new ByteArrayInputStream(getXmlByUrl(feedUrl).getBytes());
            reader.parse(new InputSource(stream));

            receiver.send(FeedResultReceiver.OK, Bundle.EMPTY);
        } catch (ParserConfigurationException e) {
            ReportException(e.toString());
        } catch (SAXException e) {
            ReportException(e.toString());
        } catch (IOException e) {
            ReportException(e.toString());
        }
    }

    private String getXmlByUrl(String url) throws IOException {
        StringBuilder builder = new StringBuilder("");

        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setRequestMethod("GET");
        connection.connect();

        if (connection.getResponseCode() == HttpURLConnection.HTTP_OK) {
            InputStream stream = connection.getInputStream();

            String contentType = connection.getContentType();
            String[] values = contentType.split(";");
            String encoding = "";

            for (String value : values) {
                value = value.trim();

                if (value.toLowerCase().startsWith("charset=")) {
                    encoding = value.substring("charset=".length());
                }
            }

            if ("".equals(encoding)) {
                encoding = connection.getContentEncoding() != null ? connection.getContentEncoding() : "utf-8";
            }

            BufferedReader reader = new BufferedReader(new InputStreamReader(stream, encoding));
            String s;
            while ((s = reader.readLine()) != null) {
                builder.append(s);
            }
        }

        return builder.toString();
    }

    @Override
    public void onDestroy() {
        running = false;
        super.onDestroy();
    }

    private void ReportException(String message) {
        Bundle b = new Bundle();
        b.putString("error", message);
        receiver.send(FeedResultReceiver.EXCEPTION_ERROR, b);
    }

    private class RSSHandler extends DefaultHandler {
        private ContentValues currentValues;
        private boolean saveText;
        private String text = "";

        @Override
        public void startDocument() {
            getContentResolver().delete(FeedContentProvider.CONTENT_POSTS_URI, FeedContentProvider.FEED_ID_FIELD + "=" + feedId, null);
        }

        @Override
        public void startElement(String string, String localName, String qName, Attributes attributes) throws SAXException {
            if (qName.equals("item") || qName.equals("entry")) {
                currentValues = new ContentValues();
            } else if (qName.equals("title") || qName.equals("description") || qName.equals("link")) {
                saveText = true;
                text = "";
            }
            if (currentValues != null && qName.equals("link")) {
                currentValues.put("url", attributes.getValue("href"));
            }
        }

        @Override
        public void endElement(String string, String localName, String qName) throws SAXException {
            saveText = false;
            if (currentValues != null) {
                if (localName.equals("title")) {
                    currentValues.put("title", text.trim());
                } else if (localName.equals("description")) {
                    currentValues.put("description", text.trim());
                } else if (localName.equals("link") && !text.isEmpty()) {
                    currentValues.put("url", text);
                } else if (localName.equals("item") || localName.equals("entry")) {
                    currentValues.put("feed_id", feedId);
                    getContentResolver().insert(FeedContentProvider.CONTENT_POSTS_URI, currentValues);
                    currentValues = null;
                }
            } else if (localName.equals("title")) {
                String newTitle = text.trim();
                if (!newTitle.equals(feedTitle)) {
                    ContentValues values = new ContentValues();
                    values.put("title", newTitle);
                    getContentResolver().update(feedUri, values, null, null);
                }
            } else if (localName.equals("description")) {
                String newDescription = text.trim();
                if (!newDescription.equals(feedDescription)) {
                    ContentValues values = new ContentValues();
                    values.put("description", newDescription);
                    getContentResolver().update(feedUri, values, null, null);
                }
            }
        }

        @Override
        public void characters(char[] ch, int start, int length) throws SAXException {
            String characters = new String(ch, start, length);
            if (saveText) text += characters;
        }
    }
}
