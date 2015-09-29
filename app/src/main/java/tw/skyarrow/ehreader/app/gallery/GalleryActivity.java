package tw.skyarrow.ehreader.app.gallery;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.support.design.widget.CoordinatorLayout;
import android.support.design.widget.Snackbar;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.ActionBar;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.support.v8.renderscript.Allocation;
import android.support.v8.renderscript.Element;
import android.support.v8.renderscript.RenderScript;
import android.support.v8.renderscript.ScriptIntrinsicBlur;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.text.style.ForegroundColorSpan;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.RatingBar;
import android.widget.TextView;

import com.facebook.drawee.backends.pipeline.Fresco;
import com.facebook.drawee.interfaces.DraweeController;
import com.facebook.drawee.view.SimpleDraweeView;
import com.facebook.imagepipeline.request.BasePostprocessor;
import com.facebook.imagepipeline.request.ImageRequest;
import com.facebook.imagepipeline.request.ImageRequestBuilder;
import com.facebook.imagepipeline.request.Postprocessor;

import java.text.DateFormat;
import java.util.Date;

import butterknife.ButterKnife;
import butterknife.InjectView;
import butterknife.OnClick;
import tw.skyarrow.ehreader.R;
import tw.skyarrow.ehreader.app.comment.CommentActivity;
import tw.skyarrow.ehreader.app.photo.PhotoActivity;
import tw.skyarrow.ehreader.app.search.SearchActivity;
import tw.skyarrow.ehreader.model.DaoSession;
import tw.skyarrow.ehreader.model.Gallery;
import tw.skyarrow.ehreader.model.GalleryDao;
import tw.skyarrow.ehreader.service.GalleryDownloadService;
import tw.skyarrow.ehreader.util.DatabaseHelper;
import tw.skyarrow.ehreader.util.FabricHelper;
import tw.skyarrow.ehreader.util.ToolbarHelper;

/**
 * Created by SkyArrow on 2015/9/26.
 */
public class GalleryActivity extends AppCompatActivity {
    public static final String GALLERY_ID = "GALLERY_ID";

    private static final DateFormat dateFormat = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT);

    @InjectView(R.id.toolbar)
    Toolbar toolbar;

    @InjectView(R.id.cover)
    SimpleDraweeView cover;

    @InjectView(R.id.coordinator)
    CoordinatorLayout coordinatorLayout;

    @InjectView(R.id.title)
    TextView titleText;

    @InjectView(R.id.subtitle)
    TextView subtitleText;

    @InjectView(R.id.rating)
    RatingBar ratingBar;

    @InjectView(R.id.info)
    TextView infoText;

    @InjectView(R.id.date)
    TextView dateText;

    @InjectView(R.id.uploader)
    TextView uploaderText;

    @InjectView(R.id.tags)
    TextView tagText;

    private long galleryId;
    private Gallery gallery;
    private DatabaseHelper dbHelper;
    private GalleryDao galleryDao;

    public static Intent intent(Context context, long galleryId) {
        Intent intent = new Intent(context, GalleryActivity.class);
        Bundle args = bundle(galleryId);

        intent.putExtras(args);

        return intent;
    }

    public static Bundle bundle(long galleryId){
        Bundle bundle = new Bundle();

        bundle.putLong(GALLERY_ID, galleryId);

        return bundle;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        FabricHelper.setupFabric(this);
        Fresco.initialize(this);
        setContentView(R.layout.activity_gallery);
        ButterKnife.inject(this);

        setSupportActionBar(toolbar);
        ActionBar actionBar = getSupportActionBar();
        actionBar.setDisplayHomeAsUpEnabled(true);
        actionBar.setDisplayShowTitleEnabled(false);

        Intent intent = getIntent();
        Bundle args = intent.getExtras();
        galleryId = args.getLong(GALLERY_ID);
        dbHelper = DatabaseHelper.get(this);
        DaoSession daoSession = dbHelper.open();
        galleryDao = daoSession.getGalleryDao();
        gallery = galleryDao.load(galleryId);

        loadGalleryInfo();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        if (gallery == null) return false;

        getMenuInflater().inflate(R.menu.gallery, menu);

        MenuItem addFavorite = menu.findItem(R.id.action_add_to_favorites);
        MenuItem removeFavorite = menu.findItem(R.id.action_remove_from_favorites);

        if (gallery.getStarred() != null && gallery.getStarred()){
            addFavorite.setVisible(false);
        } else {
            removeFavorite.setVisible(false);
        }

        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case android.R.id.home:
                ToolbarHelper.upNavigation(this);
                return true;

            case R.id.action_add_to_favorites:
                setGalleryStarred(true);
                return true;

            case R.id.action_remove_from_favorites:
                setGalleryStarred(false);
                return true;

            case R.id.action_share:
                shareGallery();
                return true;

            case R.id.action_download:
                downloadGallery();
                return true;
        }

        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onDestroy() {
        dbHelper.close();
        super.onDestroy();
    }

    private void loadGalleryInfo(){
        String[] titles = gallery.getPreferredTitles(this);

        titleText.setText(titles[0]);
        ratingBar.setRating(gallery.getRating());

        String subtitle = titles[1];

        if (TextUtils.isEmpty(subtitle)){
            subtitleText.setVisibility(View.GONE);
        } else {
            subtitleText.setText(subtitle);
        }

        String category = getString(gallery.getCategoryString());
        int categoryColor = ContextCompat.getColor(this, gallery.getCategoryColor());
        SpannableString spannableString = new SpannableString(String.format("%s / %dP", category, gallery.getCount()));
        spannableString.setSpan(new ForegroundColorSpan(categoryColor), 0, category.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        infoText.setText(spannableString);

        Date date = gallery.getCreated();
        dateText.setText(dateFormat.format(date));
        uploaderText.setText(gallery.getUploader());

        loadCoverImage();
        loadGalleryTags();
    }

    private void loadCoverImage(){
        Postprocessor postprocessor = new BasePostprocessor() {
            @Override
            public void process(Bitmap destBitmap, Bitmap sourceBitmap) {
                RenderScript rs = RenderScript.create(getApplicationContext());
                ScriptIntrinsicBlur blurScript = ScriptIntrinsicBlur.create(rs, Element.U8_4(rs));
                Allocation allIn = Allocation.createFromBitmap(rs, sourceBitmap);
                Allocation allOut = Allocation.createFromBitmap(rs, destBitmap);

                blurScript.setRadius(20f);
                blurScript.setInput(allIn);
                blurScript.forEach(allOut);
                allOut.copyTo(destBitmap);
            }
        };

        ImageRequest request = ImageRequestBuilder
                .newBuilderWithSource(Uri.parse(gallery.getThumbnail()))
                .setPostprocessor(postprocessor)
                .build();

        DraweeController controller = Fresco.newDraweeControllerBuilder()
                .setImageRequest(request)
                .setOldController(cover.getController())
                .build();

        cover.setController(controller);
    }

    private void loadGalleryTags(){
        String[] tags = gallery.getTagArray();
        SpannableString span = new SpannableString(TextUtils.join("", tags));
        int start = 0;

        for (String tag : tags){
            int end = start + tag.length();
            span.setSpan(new TagSpan(tag), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            start = end;
        }

        tagText.setText(span);
        tagText.setMovementMethod(LinkMovementMethod.getInstance());
    }

    private class TagSpan extends ClickableSpan {
        private final String tag;

        public TagSpan(String tag){
            this.tag = tag;
        }

        @Override
        public void onClick(View widget) {
            Intent intent = SearchActivity.intent(GalleryActivity.this, tag);
            startActivity(intent);
        }
    }

    private void setGalleryStarred(boolean starred){
        int message = starred ? R.string.favorites_added : R.string.favorites_removed;

        gallery.setStarred(starred);
        galleryDao.updateInTx(gallery);
        Snackbar.make(coordinatorLayout, message, Snackbar.LENGTH_LONG).show();
        invalidateOptionsMenu();
    }

    private void shareGallery(){
        Intent intent = new Intent(Intent.ACTION_SEND);

        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_TEXT, gallery.getTitle() + " " + gallery.getUrl());

        startActivity(Intent.createChooser(intent, getString(R.string.action_share)));
    }

    private void downloadGallery(){
        Intent intent = GalleryDownloadService.intent(this, galleryId);
        startService(intent);

        if (!gallery.getStarred()){
            gallery.setStarred(true);
            galleryDao.updateInTx(gallery);
        }

        Snackbar.make(coordinatorLayout, R.string.download_started, Snackbar.LENGTH_LONG).show();
    }

    @OnClick(R.id.read_btn)
    void onReadBtnPressed(){
        Intent intent = PhotoActivity.intent(this, galleryId);
        startActivity(intent);
    }

    @OnClick(R.id.comment_btn)
    void onCommentBtnPressed(){
        Intent intent = CommentActivity.intent(this, galleryId);
        startActivity(intent);
    }

    @OnClick(R.id.uploader)
    void onUploaderPressed(){
        Intent intent = SearchActivity.intent(this, "uploader:" + gallery.getUploader());
        startActivity(intent);
    }
}