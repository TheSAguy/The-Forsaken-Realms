package forge.adventure.world;

import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.utils.Disposable;
import forge.adventure.util.Serializer;
import forge.util.ScreenUtil;

import java.io.IOException;
import java.util.Date;

/**
 * Header information for the save file like a preview image, save name and saved date.
 */
public class WorldSaveHeader implements java.io.Serializable, Disposable {
    // Save compatibility: pinned 2026-09-03 (round 90) at the value derived from the v1.04 class shape so save compatibility no longer depends on the class not changing.
    private static final long serialVersionUID = 7676320057945211217L;

    public static int previewImageWidth = 512; // may cause serialization error when removed..
    public Pixmap preview;
    public String name;
    public Date saveDate;

    // Used only by a detached save snapshot. Never serialized as a field.
    private transient byte[] previewPng;

    /**
     * Must be called on the LibGDX render thread.
     * The returned header can be serialized on a worker thread.
     */
    public WorldSaveHeader snapshotForAsyncSave() throws IOException {
        WorldSaveHeader snapshot = new WorldSaveHeader();
        snapshot.name = name;
        snapshot.saveDate = saveDate == null
                ? null : new Date(saveDate.getTime());

        if (preview != null) {
            snapshot.previewPng =
                    Serializer.EncodePixmap(preview, false);
        } else {
            // Preserve the old behavior: a missing preview is saved as
            // a valid 1x1 transparent Pixmap rather than a zero-length PNG.
            Pixmap blank = new Pixmap(1, 1, Pixmap.Format.RGBA8888);
            try {
                snapshot.previewPng =
                        Serializer.EncodePixmap(blank, false);
            } finally {
                blank.dispose();
            }
        }

        return snapshot;
    }

    private void writeObject(java.io.ObjectOutputStream out) throws IOException {

        out.writeUTF(name);
        if (previewPng != null) {
            Serializer.WritePixmapData(out, previewPng);
        } else {
            if (preview == null)
                preview = new Pixmap(1, 1, Pixmap.Format.RGBA8888);
            Serializer.WritePixmap(out, preview, false);
        }
        out.writeObject(saveDate);
    }

    private void readObject(java.io.ObjectInputStream in) throws IOException, ClassNotFoundException {
        name = in.readUTF();
        if(preview!=null)
            preview.dispose();
        preview = Serializer.ReadPixmap(in);
        saveDate = (Date) in.readObject();

    }

    public void dispose() {
        preview.dispose();
    }

    public void createPreview() {
        try {
            if (preview != null)
                preview.dispose();
            preview = ScreenUtil.getInstance().getThumbnailPreview();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}