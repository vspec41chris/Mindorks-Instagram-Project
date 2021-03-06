package com.mindorks.bootcamp.instagram.ui.profile

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Transformations
import com.mindorks.bootcamp.instagram.data.model.Image
import com.mindorks.bootcamp.instagram.data.model.Post
import com.mindorks.bootcamp.instagram.data.model.User
import com.mindorks.bootcamp.instagram.data.remote.Networking
import com.mindorks.bootcamp.instagram.data.repository.PostRepository
import com.mindorks.bootcamp.instagram.data.repository.ProfileRepository
import com.mindorks.bootcamp.instagram.data.repository.UserRepository
import com.mindorks.bootcamp.instagram.ui.base.BaseViewModel
import com.mindorks.bootcamp.instagram.ui.liked_by.LikedByParcelize
import com.mindorks.bootcamp.instagram.utils.common.Event
import com.mindorks.bootcamp.instagram.utils.common.Notify
import com.mindorks.bootcamp.instagram.utils.common.NotifyFor
import com.mindorks.bootcamp.instagram.utils.common.Resource
import com.mindorks.bootcamp.instagram.utils.log.Logger
import com.mindorks.bootcamp.instagram.utils.network.NetworkHelper
import com.mindorks.bootcamp.instagram.utils.rx.SchedulerProvider
import io.reactivex.disposables.CompositeDisposable

class ProfileViewModel(
    schedulerProvider: SchedulerProvider,
    compositeDisposable: CompositeDisposable,
    networkHelper: NetworkHelper,
    private val userRepository: UserRepository,
    private val profileRepository: ProfileRepository,
    private val postRepository: PostRepository,
    private val myPostsList: ArrayList<Post>
) : BaseViewModel(schedulerProvider, compositeDisposable, networkHelper) {

    val user = userRepository.getCurrentUser()!!

    private val headers = mapOf(
        Pair(Networking.HEADER_API_KEY, Networking.API_KEY),
        Pair(Networking.HEADER_USER_ID, user.id),
        Pair(Networking.HEADER_ACCESS_TOKEN, user.accessToken)
    )

    val launchLogout: MutableLiveData<Event<Unit>> = MutableLiveData()
    val launchEditProfile: MutableLiveData<Event<User>> = MutableLiveData()
    val launchLikedBy: MutableLiveData<Event<LikedByParcelize>> = MutableLiveData()

    val loading: MutableLiveData<Boolean> = MutableLiveData()
    val loggingOut: MutableLiveData<Boolean> = MutableLiveData()
    val name: MutableLiveData<String> = MutableLiveData()
    val bio: MutableLiveData<String> = MutableLiveData()
    val profilePicUrl: MutableLiveData<String> = MutableLiveData()
    val profileImage: LiveData<Image> = Transformations.map(profilePicUrl) {
        it?.run { Image(this, headers) }
    }
    val refreshPosts: MutableLiveData<Resource<List<Post>>> = MutableLiveData()
    val postsCount: LiveData<Int> = Transformations.map(refreshPosts) { it.data?.count() }
    val notifyHome: MutableLiveData<Event<NotifyFor<Post>>> = MutableLiveData()

    override fun onCreate() {
        fetchProfile()
    }

    fun onEditProfileClicked() = launchEditProfile.postValue(Event(user))

    fun refreshProfileData() {
        loading.postValue(true)

        if (checkInternetConnectionWithMessage())
            compositeDisposable.add(
                profileRepository.fetchProfile(user)
                    .subscribeOn(schedulerProvider.io())
                    .subscribe(
                        {
                            if (name.value != it.name) name.postValue(it.name)
                            if (bio.value != it.bio) bio.postValue(it.bio)
                            if (profilePicUrl.value != it.profilePicUrl) profilePicUrl.postValue(it.profilePicUrl)

                            loading.postValue(false)
                        },
                        {
                            handleNetworkError(it)
                        }
                    )
            )
        else loading.postValue(false)
    }

    private fun onNewPost(post: Post) {
        myPostsList.add(0, post)
        refreshPosts.postValue(Resource.success(mutableListOf<Post>().apply { addAll(myPostsList) }))
    }

    fun onLikeClick(post: Post, doNotifyHome: Boolean) =
        if (doNotifyHome)
            notifyHome.postValue(Event(NotifyFor.like(post)))
        else {
            myPostsList.run { forEachIndexed { i, p -> if (p.id == post.id) this[i] = post } }
            refreshPosts.postValue(Resource.success(mutableListOf<Post>().apply { addAll(myPostsList) }))
        }

    fun onDeleteClick(post: Post, doNotifyHome: Boolean) {
        myPostsList.removeAll { it.id == post.id }
        refreshPosts.postValue(Resource.success(myPostsList))
        if (doNotifyHome) notifyHome.postValue(Event(NotifyFor.delete(post)))
    }

    private fun onNameChange(newName: String) {
        Logger.d(ProfileFragment.TAG, "onNameChange")
        myPostsList.run {
            forEachIndexed { i, p ->
                // Find post and replace with new Post
                if (this[i].creator.id == user.id && p.creator.name != newName) this[i] =
                    Post(
                        p.id, p.imageUrl, p.imageWidth, p.imageHeight,
                        Post.User(p.creator.id, newName, p.creator.profilePicUrl),
                        p.likedBy, p.createdAt
                    )
            }
        }
        refreshPosts.postValue(Resource.success(mutableListOf<Post>().apply { addAll(myPostsList) }))
    }

    private fun onProfileImageChange(newProfilePicUrl: String) {
        myPostsList.run {
            forEachIndexed { i, p ->
                // Find post and replace with new Post
                if (this[i].creator.id == user.id && p.creator.profilePicUrl != newProfilePicUrl)
                    this[i] = Post(
                        p.id, p.imageUrl, p.imageWidth, p.imageHeight,
                        Post.User(p.creator.id, p.creator.name, newProfilePicUrl),
                        p.likedBy, p.createdAt
                    )
            }
        }
        refreshPosts.postValue(Resource.success(mutableListOf<Post>().apply { addAll(myPostsList) }))
    }

    fun onLikesCountClick(post: Post) {
        post.likedBy?.let {
            launchLikedBy.postValue(
                Event(
                    // Creating Parcelable object to pass to another activity
                    LikedByParcelize(
                        // Creating a list
                        arrayListOf<LikedByParcelize.User>().run {
                            // Converting mutable list to List with parcelable User object
                            it.forEach { user ->
                                add(LikedByParcelize.User(user.id, user.name, user.profilePicUrl))
                            }
                            toList()    // Returning as List
                        }
                    )
                )
            )
        }
    }

    fun onPostChange(change: NotifyFor<Any>) {
        when (change.state) {
            Notify.NEW_POST -> onNewPost(change.data as Post)

            Notify.LIKE -> onLikeClick(change.data as Post, false)

            Notify.DELETE -> onDeleteClick(change.data as Post, false)

            Notify.NAME -> onNameChange(change.data as String)

            Notify.PROFILE_IMAGE -> onProfileImageChange(change.data as String)

            else -> {}
        }
    }

    fun onLogoutClicked() {
        loggingOut.postValue(true)

        if (checkInternetConnectionWithMessage())
            compositeDisposable.add(
                userRepository.doLogout(user)
                    .subscribeOn(schedulerProvider.io())
                    .subscribe(
                        { status ->
                            if (status) {
                                userRepository.removeCurrentUser()
                                launchLogout.postValue(Event(Unit))
                            }
                            loggingOut.postValue(false)
                        },
                        {
                            handleNetworkError(it)
                            loggingOut.postValue(false)
                        }
                    )
            )
        else loggingOut.postValue(false)
    }

    private fun fetchProfile() {
        loading.postValue(true)
        myPostsList.clear() // Just to be sure we have empty list before fetching

        if (checkInternetConnectionWithMessage())
            compositeDisposable.addAll(
                profileRepository.fetchProfile(user)
                    .subscribeOn(schedulerProvider.io())
                    .subscribe(
                        {
                            name.postValue(it.name)
                            bio.postValue(it.bio)
                            profilePicUrl.postValue(it.profilePicUrl)
                        },
                        {
                            handleNetworkError(it)
                        }
                    ),
                postRepository.fetchMyPostList(user)
                    .doAfterSuccess { if (it.count() == 0) loading.postValue(false) }
                    .subscribeOn(schedulerProvider.io())
                    .subscribe(
                        { myPosts ->
                            myPosts.forEach { myPost ->
                                postRepository.fetchPostDetail(myPost, user)
                                    .doFinally {
                                        // Checks for all requests are completed
                                        if (myPosts.count() == myPostsList.count()) {
                                            myPostsList.sortByDescending { it.createdAt }
                                            refreshPosts.postValue(Resource.success(myPostsList))
                                            Logger.d(ProfileFragment.TAG, "$myPostsList")
                                            loading.postValue(false)
                                        }
                                    }
                                    .subscribeOn(schedulerProvider.io())
                                    .subscribe(
                                        { post -> myPostsList.add(post) },
                                        {
                                            handleNetworkError(it)
                                            loading.postValue(false)
                                        }
                                    )
                            }
                        },
                        {
                            handleNetworkError(it)
                            loading.postValue(false)
                        }
                    )
            )
        else loading.postValue(false)
    }
}

