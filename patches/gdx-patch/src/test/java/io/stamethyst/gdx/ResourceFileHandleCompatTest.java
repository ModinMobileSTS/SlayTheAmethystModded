package io.stamethyst.gdx;

import com.badlogic.gdx.Files.FileType;
import java.io.File;
import org.junit.Test;
import static org.junit.Assert.*;

public class ResourceFileHandleCompatTest {
    @Test public void resourcePathsNormalizeMixedSeparatorsAndKeepNames() {
        for (FileType type : new FileType[]{FileType.Internal, FileType.Classpath}) {
            assertEquals("mod/角色 models/../textures/skin.png",
                ResourceFileHandleCompat.normalize("mod\\角色 models/../textures\\skin.png", type));
            assertEquals("mod/models/skin.png",
                ResourceFileHandleCompat.normalize(new File("mod\\models\\skin.png"), type).getPath());
            assertEquals("", ResourceFileHandleCompat.normalize("", type));
        }
    }

    @Test public void fileSystemPathsRetainLiteralBackslashesAndIdentity() {
        String path = "saves\\literal-name";
        File file = new File(path);
        for (FileType type : new FileType[]{FileType.Absolute, FileType.Local, FileType.External}) {
            assertSame(path, ResourceFileHandleCompat.normalize(path, type));
            assertSame(file, ResourceFileHandleCompat.normalize(file, type));
        }
    }

    @Test public void portableFilesAreNotReplacedAndNormalizationIsIdempotent() {
        File file = new File("mod/models/skin.png");
        assertSame(file, ResourceFileHandleCompat.normalize(file, FileType.Internal));
        File converted = ResourceFileHandleCompat.normalize(new File("mod\\models\\skin.png"), FileType.Classpath);
        assertSame(converted, ResourceFileHandleCompat.normalize(converted, FileType.Classpath));
    }

    @Test public void nullArgumentsKeepExistingConstructorSemantics() {
        assertNull(ResourceFileHandleCompat.normalize((String) null, FileType.Internal));
        assertNull(ResourceFileHandleCompat.normalize((File) null, FileType.Classpath));
        File file = new File("mod\\image.png");
        assertSame(file, ResourceFileHandleCompat.normalize(file, null));
        assertEquals("mod\\image.png", ResourceFileHandleCompat.normalize(file.getPath(), null));
    }
}
