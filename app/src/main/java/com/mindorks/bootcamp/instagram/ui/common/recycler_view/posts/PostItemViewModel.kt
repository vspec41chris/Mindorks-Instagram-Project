package com.mindorks.bootcamp.instagram.ui.common.recycler_view.posts

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Transformations
import com.mindorks.bootcamp.instagram.R
import com.mindorks.bootcamp.instagram.data.model.Image
import com.mindorks.bootcamp.instagram.data.model.Post
import com.mindorks.bootcamp.instagram.data.remote.Networking
import com.mindorks.bootcamp.instagram.data.repository.PostRepository
import com.mindorks.bootcamp.instagram.data.repository.UserRepository
import com.mindorks.bootcamp.instagram.ui.base.BaseItemViewModel
import com.mindorks.bootcamp.instagram.utils.common.Event
import com.mindorks.bootcamp.instagram.utils.common.Resource
import com.mindorks.bootcamp.instagram.utils.common.TimeUtils
import com.mindorks.bootcamp.instagram.utils.display.ScreenUtils
import com.mindorks.bootcamp.instagram.utils.network.NetworkHelper
import com.mindorks.bootcamp.instagram.utils.rx.SchedulerProvider
import io.reactivex.disposables.CompositeDisposable
import javax.inject.Inject

class PostItemViewModel @Inject constructor(
    schedulerProvider: SchedulerProvider,
    compositeDisposable: CompositeDisposable,
    networkHelper: NetworkHelper,
    userRepository: UserRepository,
    private val postRepository: PostRepository
): BaseItemViewModel<Post>(schedulerProvider, compositeDisposable, networkHelper) {

    companion object {
        const val TAG = "PostItemViewModel"
    }

    private val user = userRepository.getCurrentUser()!!
    //for scalling image so that will fit into each device
    private val screenWidth = ScreenUtils.getScreenWidth()
    private val screenHeight = ScreenUtils.getScreenHeight()
    // header for network call authentication
    private val headers = mapOf(
        Pair(Networking.HEADER_API_KEY, Networking.API_KEY),
        Pair(Networking.HEADER_USER_ID, user.id),
        Pair(Networking.HEADER_ACCESS_TOKEN, user.accessToken)
    )

    // LiveData UI Need to Observe***************
    val postDeleted: MutableLiveData<Event<Post>> = MutableLiveData()
    val likeClicked: MutableLiveData<Event<Post>> = MutableLiveData()
    val likesCountClicked: MutableLiveData<Event<Post>> = MutableLiveData()
    val isOwner: LiveData<Boolean> = Transformations.map(data) { it.creator.id == user.id}

    // Return data
    val name: LiveData<String> = Transformations.map(data) { it.creator.name }
    // go to timeUtils helper class -> print date to UI
    val postTime: LiveData<String> = Transformations.map(data) { TimeUtils.getTimeAgo(it.createdAt) }
    val likesCount: LiveData<Int> = Transformations.map(data) { it.likedBy?.size ?: 0 }
    val isLiked: LiveData<Boolean> = Transformations.map(data) {
        it.likedBy?.find { postUser -> postUser.id == user.id } !== null
    }

    val profileImage: LiveData<Image> = Transformations.map(data) {
        it.creator.profilePicUrl?.run { Image(this, headers) }
    }

    //fun for making image take whole space Screen width
    val imageDetail: LiveData<Image> = Transformations.map(data) {
        Image(
            it.imageUrl,
            headers,
            //taking screen width
            screenWidth,
            // Height wll check aspect ratio using calculate ScaleFactor
            it.imageHeight?.let { height ->
                return@let (calculateScaleFactor(it) * height).toInt()
            } ?: screenHeight / 3)
    }

    override fun onCreate() {}

    //**********************************************************************************************
    // What is the difference between imagewidth & screenwidth then * imageheight
    private fun calculateScaleFactor(post: Post) =
        post.imageWidth?.let { return@let screenWidth.toFloat() / it } ?: 1f

    fun onDeleteClick() = data.value?.let {
        if (networkHelper.isNetworkConnected()) {
            compositeDisposable.add(
                postRepository.makeDeletePost(it, user)
                    .subscribeOn(schedulerProvider.io())
                    .subscribe(
                        { response ->
                            if (response) postDeleted.postValue(Event(data.value!!))
                        },
                        { error -> handleNetworkError(error) }
                    )
            )
        } else {
            messageStringId.postValue(Resource.error(R.string.network_connection_error))
        }
    }

    //*********************************************
    fun onLikeClick() = data.value?.let {
        if (networkHelper.isNetworkConnected()) {
            val api =
                if (isLiked.value == true)
                    postRepository.makeUnlikePost(it, user)
                else
                    postRepository.makeLikePost(it, user)

            compositeDisposable.add(api
                .subscribeOn(schedulerProvider.io())
                .subscribe(
                    { responsePost ->
                        if (responsePost.id == it.id) {
                            likeClicked.postValue(Event(data.value!!))
                            updateData(responsePost)
                        }
                    },
                    { error -> handleNetworkError(error) }
                )
            )
        } else {
            messageStringId.postValue(Resource.error(R.string.network_connection_error))
        }
    }

    fun onLikesCountClick() = likesCountClicked.postValue(Event(data.value!!))
}