package com.example.fastaidemo;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.res.ResourcesCompat;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import org.pytorch.IValue;
import org.pytorch.LiteModuleLoader;
import org.pytorch.Module;
import org.pytorch.Tensor;
import org.pytorch.torchvision.TensorImageUtils;
import org.pytorch.MemoryFormat;

import com.google.android.material.floatingactionbutton.FloatingActionButton;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;

public class MainActivity extends AppCompatActivity {

    // Button that allow to change image using gallery images
    FloatingActionButton galleryButton;

    // View for loading the image
    ImageView imageView;

    // View for writing the image classification text
    TextView textView;

    // Pytorch model
    Module module;

    // Permissions for accessing the library
    final String[] GALLERY_PERMISSIONS = {
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
    };

    // Flag for enable/disable running validation when launching the app
    final boolean RUN_VALIDATION = false;
    // Validation percentage of image used (for >10 the process is very time consuming)
    final int RUN_VALIDATION_PCT = 1;

    // Internal request codes that are lunched by the app
    // Request for storage permissions
    final int REQUEST_GALLERY_PERMISSIONS = 1;
    // Request for image on gallery selection (external content)
    final int REQUEST_ADD_FILE = 2;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // This already was here with the empty activity:
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Request gallery permissions
        ActivityCompat.requestPermissions(MainActivity.this, GALLERY_PERMISSIONS, REQUEST_GALLERY_PERMISSIONS);

        // Associate elements from layout with their respective handler class
        imageView = findViewById(R.id.imageView);
        galleryButton = findViewById(R.id.galleryFloatingActionButton);
        textView = findViewById(R.id.textView);

        // Show initial text with instructions
        textView.setText("Choose an image to classify between cat and dog");

        // Here we load the assets: the only one is the pytorch model.
        try {
            module = LiteModuleLoader.load(assetFilePath(this, "my_model.ptl"));

            // Code below is for checking that load + tensor convert to float + normalization is the same here and in Android
            Bitmap assetImage = BitmapFactory.decodeFile(assetFilePath(this, "cat1.jpg"));
            final Tensor tensor = TensorImageUtils.bitmapToFloat32Tensor(assetImage,
                    TensorImageUtils.TORCHVISION_NORM_MEAN_RGB, TensorImageUtils.TORCHVISION_NORM_STD_RGB, MemoryFormat.CHANNELS_LAST);
            System.out.println("inputTensor[100,200,0] = " + tensor.getDataAsFloatArray()[0+100*3+200*3*720]);
            System.out.println("inputTensor[44,123,2] = " + tensor.getDataAsFloatArray()[2+44*3+123*3*720]);

            if (RUN_VALIDATION) {
                String[] validation_fns = getAssets().list("validation/");
                System.out.println("Running validation set from: " + validation_fns.length);
                int processedImages = 0, processedCatImages = 0, processedDogImages = 0, isCorrectPrediction=0;
                for (int i = 0; i < validation_fns.length ; ++i)
                    if (i%100 < RUN_VALIDATION_PCT) {
                        String assetFn = "validation/"+validation_fns[i];
                        InputStream istr = getAssets().open(assetFn);
                        Bitmap bitmap = BitmapFactory.decodeStream(istr);
                        istr.close();
                        if (bitmap!=null) {
                            float[] scores = predictionCatOrDog(bitmap, false);
                            int predictedClass = argMax(scores);

                            /*if (i%100==0)*/
                            System.out.println(validation_fns[i]);
                            System.out.println(Character.isUpperCase(validation_fns[i].charAt(0)) ? "cat" : "dog");
                            int realClass = Character.isUpperCase(validation_fns[i].charAt(0)) ? 0 : 1;
                            System.out.println(predictedClass + " " + realClass);
                            if ( ++processedImages % 100 == 0 ) System.out.println(processedImages);
                            if (realClass==0) ++processedDogImages; else ++processedCatImages;
                            isCorrectPrediction += (predictedClass==realClass) ? 1 : 0;
                         }
                }
                System.out.println(String.format("Cats: %d (%.2f) Dogs: %d (%.2f) Acc: %.8f",processedCatImages,((float)processedCatImages)/processedImages,
                        processedDogImages,((float)processedDogImages)/processedImages,((float)isCorrectPrediction)/processedImages));
            }

        } catch (IOException e) {
            Log.e("FastAIDemo", "Error reading assets", e);
            finish();
        }
    }

    // This is the method executed when the button is pressed (read activity_main.xml FloatingActionButton entry)
    // This way of choosing a file from gallery is deprecated. If you want to modify it PRs are welcome!
    public void addFileOnClick(View unused) {
        Intent i = new Intent(
                Intent.ACTION_PICK,
                android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
        startActivityForResult(i, REQUEST_ADD_FILE);
    }

    // This will handle the REQUEST_ADD_FILE request from above
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_ADD_FILE && resultCode == RESULT_OK && null != data) {
            Uri selectedImage = data.getData();
            String[] filePathColumn = {MediaStore.Images.Media.DATA};
            Cursor cursor = getContentResolver().query(selectedImage,
                    filePathColumn, null, null, null);
            cursor.moveToFirst();
            int columnIndex = cursor.getColumnIndex(filePathColumn[0]);
            String picturePath = cursor.getString(columnIndex);
            cursor.close();
            ImageView imageView = (ImageView) findViewById(R.id.imageView);
            Bitmap bitmap = BitmapFactory.decodeFile(picturePath);
            imageView.setImageBitmap(bitmap);

            // Code above was (almost) all copied (https://www.viralpatel.net/pick-image-from-galary-android-app/ thanks)
            // This call the inference method and show the results on the text view
            final float[] scores = predictionCatOrDog(bitmap, true);
            int maxScoreIdx = argMax(scores);
            System.out.println("maxScoreIdx: " + maxScoreIdx);

            String[] CLASSES = new String[]{ "cat", "dog" };
            System.out.println("Predicted class: " + CLASSES[maxScoreIdx]);

            textView.setText(String.format("Prediction: %s (%.2f %% cat | %.2f %% dog)", CLASSES[maxScoreIdx], scores[0]*100, scores[1]*100));
        }
    }

    // Here we do all the pytorch stuff (except with loading the model, done in onCreate) and return the predicted class probabilities
    private float[] predictionCatOrDog(Bitmap bitmap, boolean verbose) {
        // Convert bitmap to Float32 tensor is equivalent to:
        // - Load the image (pixels as 0 to 255 bytes).
        // - Apply torchvision.transforms.ToTensor, scaleing values from 0 to 1 (dividing by 255).
        // - Apply transforms.Normalize((0.485, 0.456, 0.406), (0.229, 0.224, 0.225))
        // You don't need the resize because ResNet use AdaptiveAvgPool2d
        bitmap = Bitmap.createScaledBitmap(bitmap, 224, 224, true);
        final Tensor inputTensor = TensorImageUtils.bitmapToFloat32Tensor(bitmap,
                TensorImageUtils.TORCHVISION_NORM_MEAN_RGB, TensorImageUtils.TORCHVISION_NORM_STD_RGB, MemoryFormat.CHANNELS_LAST);

        if (verbose) System.out.println("Shape: " + Arrays.toString(inputTensor.shape()));

        // Forward pass, run the model
        // We do not resize to 224 x 224 thanks to AdaptiveAvgPool2d (but it could be a good idea to speed up inference process)
        // In production this SHOULD NOT be done in the main thread because is a lot of work and will block the app
        if (verbose) System.out.println("Forward begin");
        final Tensor outputTensor = module.forward(IValue.from(inputTensor)).toTensor();
        if (verbose) System.out.println("Forward ends");

        // Getting tensor content as java array of floats
        final float[] scores = outputTensor.getDataAsFloatArray();
        if (verbose) System.out.println("scores: " + Arrays.toString(scores));
        return scores;
    }

    private int argMax(float[] scores) {
        // Searching for the index with maximum score
        int maxScoreIdx = 0;
        for (int i = 0; i < scores.length; i++) {
            if (scores[i] > scores[maxScoreIdx]) {
                maxScoreIdx = i;
            }
        }
        return maxScoreIdx;
    }

    // This function was copied from HelloWorldApp (android-demo-app from pytorch's repo)
    // It returns the path of the asset on the file dir
    // The first time is called for an asset it copies the asset from the asset location to file dir
    public static String assetFilePath(Context context, String assetName) throws IOException {
        File file = new File(context.getFilesDir(), assetName);
        if (file.exists() && file.length() > 0) {
            return file.getAbsolutePath();
        }

        try (InputStream is = context.getAssets().open(assetName)) {
            try (OutputStream os = new FileOutputStream(file)) {
                byte[] buffer = new byte[4 * 1024];
                int read;
                while ((read = is.read(buffer)) != -1) {
                    os.write(buffer, 0, read);
                }
                os.flush();
            }
            return file.getAbsolutePath();
        }
    }
}