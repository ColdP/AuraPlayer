-keepattributes Signature,InnerClasses,EnclosingMethod
-keep class org.jaudiotagger.** { *; }
-keep class ai.onnxruntime.** { *; }
-keep class btm.m.auraplayer.rear.RearScreenShellService { *; }
-keep class btm.m.auraplayer.audio.usb.UsbAudioStream { *; }

# Desktop AWT/ImageIO/Swing references inside jaudiotagger unused on Android
-dontwarn java.awt.Graphics2D
-dontwarn java.awt.Image
-dontwarn java.awt.geom.AffineTransform
-dontwarn java.awt.image.BufferedImage
-dontwarn java.awt.image.ImageObserver
-dontwarn java.awt.image.RenderedImage
-dontwarn javax.imageio.ImageIO
-dontwarn javax.imageio.ImageWriter
-dontwarn javax.imageio.stream.ImageInputStream
-dontwarn javax.imageio.stream.ImageOutputStream
-dontwarn javax.swing.filechooser.FileFilter
-dontwarn sun.security.action.GetPropertyAction
