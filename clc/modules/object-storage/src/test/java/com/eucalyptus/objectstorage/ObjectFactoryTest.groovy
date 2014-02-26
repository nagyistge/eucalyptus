package com.eucalyptus.objectstorage

import com.eucalyptus.auth.Accounts
import com.eucalyptus.auth.principal.User
import com.eucalyptus.objectstorage.entities.Bucket
import com.eucalyptus.objectstorage.entities.ObjectEntity
import com.eucalyptus.objectstorage.entities.PartEntity
import com.eucalyptus.objectstorage.exceptions.s3.NoSuchKeyException
import com.eucalyptus.objectstorage.msgs.GetObjectResponseType
import com.eucalyptus.objectstorage.msgs.GetObjectType
import com.eucalyptus.objectstorage.providers.InMemoryProvider
import com.eucalyptus.objectstorage.providers.ObjectStorageProviderClient
import com.eucalyptus.objectstorage.util.AclUtils
import com.eucalyptus.objectstorage.util.ObjectStorageProperties
import com.eucalyptus.storage.msgs.s3.AccessControlList
import com.eucalyptus.storage.msgs.s3.AccessControlPolicy
import com.eucalyptus.storage.msgs.s3.MetaDataEntry
import com.eucalyptus.storage.msgs.s3.Part
import com.eucalyptus.util.EucalyptusCloudException
import groovy.transform.CompileStatic
import org.apache.log4j.Logger;
import org.junit.After
import org.junit.AfterClass;
import org.junit.Before
import org.junit.BeforeClass;
import org.junit.Test
import static org.junit.Assert.fail

/**
 * Created by zhill on 1/28/14.
 */
@CompileStatic
public class ObjectFactoryTest {
    private static final Logger LOG = Logger.getLogger(ObjectFactoryTest.class);
    static ObjectStorageProviderClient provider = new InMemoryProvider()

    @BeforeClass
    public static void beforeClass() throws Exception {
        UnitTestSupport.setupAuthPersistenceContext()
        UnitTestSupport.setupOsgPersistenceContext()
        UnitTestSupport.initializeAuth(2, 2)
    }

    @AfterClass
    public static void afterClass() throws Exception {
        UnitTestSupport.tearDownOsgPersistenceContext()
        UnitTestSupport.tearDownAuthPersistenceContext()
    }
    @Before
    public void setUp() throws Exception {
        provider.start()
        UnitTestSupport.flushObjects()
        UnitTestSupport.flushBuckets()
    }

    @After
    public void tearDown() throws Exception {
        provider.stop()
        UnitTestSupport.flushObjects()
        UnitTestSupport.flushBuckets()
    }

    /**
     * Expect creation to complete and result in 'extant' object
     * @throws Exception
     */
    @Test
    public void testCreateObject() throws Exception {
        User user = Accounts.lookupUserById(UnitTestSupport.getUsersByAccountName(UnitTestSupport.getTestAccounts().first()).first())
        String canonicalId = user.getAccount().getCanonicalId()
        AccessControlPolicy acp = new AccessControlPolicy()
        acp.setAccessControlList(new AccessControlList())
        acp = AclUtils.processNewResourcePolicy(user, acp, canonicalId)

        Bucket bucket = Bucket.getInitializedBucket("testbucket", user.getUserId(), acp, null)
        bucket = OsgBucketFactory.getFactory().createBucket(provider, bucket, UUID.randomUUID().toString(), user)

        assert(bucket != null)
        assert(bucket.getState().equals(BucketState.extant))
        byte[] content = 'fakecontent123'.getBytes()
        def key = 'testkey'

        ObjectEntity objectEntity = ObjectEntity.newInitializedForCreate(bucket, key, content.length, user)
        objectEntity.setAcl(acp)
        ArrayList<MetaDataEntry> metadata = new ArrayList<>();
        metadata.add(new MetaDataEntry("key1", "value1"))
        metadata.add(new MetaDataEntry("key2", "value2"))
        metadata.add(new MetaDataEntry("key3", "value3"))

        ObjectEntity resultEntity = OsgObjectFactory.getFactory().createObject(provider, objectEntity, new ByteArrayInputStream(content), metadata, user)

        assert(resultEntity != null)
        assert(resultEntity.getState().equals(ObjectState.extant))
        ObjectEntity fetched = ObjectMetadataManagers.getInstance().lookupObject(bucket, key, null)
        assert(fetched.geteTag().equals(resultEntity.geteTag()))

        GetObjectType request = new GetObjectType()
        request.setUser(user)
        request.setKey(resultEntity.getObjectUuid())
        request.setBucket(bucket.getBucketUuid())
        GetObjectResponseType response = provider.getObject(request)
        assert(response.getEtag().equals(resultEntity.geteTag()))
        assert(response.getSize().equals(resultEntity.getSize()))
        byte[] buffer = new byte[content.length]
        response.getDataInputStream().read(buffer)
        for(int i = 0; i < buffer.size(); i++) {
            assert(buffer[i] == content[i])
        }

        //Check metadata
        assert(response.metaData.size() == metadata.size())
        assert(response.metaData.containsAll(metadata))

    }

    /**
     * Expect creation to complete and result in 'extant' object
     * 2nd creation should overwrite the first, lookup should return
     * the 2nd object.
     * @throws Exception
     */
    @Test
    public void testCreateDuplicateObject() throws Exception {
        User user = Accounts.lookupUserById(UnitTestSupport.getUsersByAccountName(UnitTestSupport.getTestAccounts().first()).first())
        String canonicalId = user.getAccount().getCanonicalId()
        AccessControlPolicy acp = new AccessControlPolicy()
        acp.setAccessControlList(new AccessControlList())
        acp = AclUtils.processNewResourcePolicy(user, acp, canonicalId)

        Bucket bucket = Bucket.getInitializedBucket("testbucket", user.getUserId(), acp, null)
        bucket = OsgBucketFactory.getFactory().createBucket(provider, bucket, UUID.randomUUID().toString(), user)

        assert(bucket != null)
        assert(bucket.getState().equals(BucketState.extant))
        byte[] content = 'fakecontent123'.getBytes()
        def key = 'testkey'

        ObjectEntity objectEntity = ObjectEntity.newInitializedForCreate(bucket, key, content.length, user)
        objectEntity.setAcl(acp)
        ObjectEntity resultEntity = OsgObjectFactory.getFactory().createObject(provider, objectEntity, new ByteArrayInputStream(content), null, user)

        assert(resultEntity != null)
        assert(resultEntity.getState().equals(ObjectState.extant))
        ObjectEntity fetched = ObjectMetadataManagers.getInstance().lookupObject(bucket, key, null)
        assert(fetched.geteTag().equals(resultEntity.geteTag()))

        GetObjectType request = new GetObjectType()
        request.setUser(user)
        request.setKey(resultEntity.getObjectUuid())
        request.setBucket(bucket.getBucketUuid())
        GetObjectResponseType response = provider.getObject(request)
        assert(response.getEtag().equals(resultEntity.geteTag()))
        assert(response.getSize().equals(resultEntity.getSize()))
        byte[] buffer = new byte[content.length]
        response.getDataInputStream().read(buffer)
        for(int i = 0; i < buffer.size(); i++) {
            assert(buffer[i] == content[i])
        }

        byte[] content2 = 'fakecontent123fakecontent123morecontent'.getBytes()

        ObjectEntity objectEntity2 = ObjectEntity.newInitializedForCreate(bucket, key, content2.length, user)
        objectEntity2.setAcl(acp)
        ObjectEntity resultEntity2 = OsgObjectFactory.getFactory().createObject(provider, objectEntity2, new ByteArrayInputStream(content2), null, user)

        assert(resultEntity2 != null)
        assert(resultEntity2.getState().equals(ObjectState.extant))
        ObjectEntity fetched2 = ObjectMetadataManagers.getInstance().lookupObject(bucket, key, null)
        assert(fetched2.geteTag().equals(resultEntity2.geteTag()))
        assert(!fetched2.geteTag().equals(fetched.geteTag()))
        assert(fetched2.getObjectUuid().equals(resultEntity2.getObjectUuid()))
        assert(!fetched2.getObjectUuid().equals(resultEntity.getObjectUuid()))
    }

    /**
     * Expect deletion to fully remove the object on the backend and
     * in metadata.
     * @throws Exception
     */
    @Test
    public void testFullDeleteObject() throws Exception {
        User user = Accounts.lookupUserById(UnitTestSupport.getUsersByAccountName(UnitTestSupport.getTestAccounts().first()).first())
        String canonicalId = user.getAccount().getCanonicalId()
        AccessControlPolicy acp = new AccessControlPolicy()
        acp.setAccessControlList(new AccessControlList())
        acp = AclUtils.processNewResourcePolicy(user, acp, canonicalId)

        Bucket bucket = Bucket.getInitializedBucket("testbucket", user.getUserId(), acp, null)
        bucket = OsgBucketFactory.getFactory().createBucket(provider, bucket, UUID.randomUUID().toString(), user)

        assert(bucket != null)
        assert(bucket.getState().equals(BucketState.extant))
        byte[] content = 'fakecontent123'.getBytes()

        ObjectEntity objectEntity = ObjectEntity.newInitializedForCreate(bucket, "testket", content.length, user)
        objectEntity.setAcl(acp)
        ObjectEntity resultEntity = OsgObjectFactory.getFactory().createObject(provider, objectEntity, new ByteArrayInputStream(content), null, user)

        assert(resultEntity != null)
        assert(resultEntity.getState().equals(ObjectState.extant))

        //Do the delete logically
        OsgObjectFactory.getFactory().logicallyDeleteObject(objectEntity, user)

        try {
            ObjectEntity found = ObjectMetadataManagers.getInstance().lookupObject(bucket, "testkey", null)
            fail('Should have gotten no-key exception on metadata lookup')
        } catch(NoSuchElementException e) {
            //Correctly caught this exception
        }

        //Lookup record directly
        List<ObjectEntity> objs = ObjectMetadataManagers.getInstance().lookupObjectsInState(objectEntity.getBucket(), objectEntity.getObjectKey(), null, ObjectState.deleting)


        //Actually do the delete
        OsgObjectFactory.getFactory().actuallyDeleteObject(provider, objectEntity, user)

        GetObjectType request = new GetObjectType()
        request.setUser(user)
        request.setKey(resultEntity.getObjectUuid())
        request.setBucket(bucket.getBucketUuid())
        try {
            GetObjectResponseType response = provider.getObject(request)
            fail('Should not find key on backend: ' + response.getEtag())
        } catch(EucalyptusCloudException e) {
            //Correctly caught the exception as expected
        }
    }

    /**
     * Expect deletion to fully delete the object regardless of its 'deleting'
     * state
     * @throws Exception
     */
    @Test
    public void testDeleteAlreadyDeletingObject() throws Exception {
        User user = Accounts.lookupUserById(UnitTestSupport.getUsersByAccountName(UnitTestSupport.getTestAccounts().first()).first())
        String canonicalId = user.getAccount().getCanonicalId()
        AccessControlPolicy acp = new AccessControlPolicy()
        acp.setAccessControlList(new AccessControlList())
        acp = AclUtils.processNewResourcePolicy(user, acp, canonicalId)

        Bucket bucket = Bucket.getInitializedBucket("testbucket", user.getUserId(), acp, null)
        bucket = OsgBucketFactory.getFactory().createBucket(provider, bucket, UUID.randomUUID().toString(), user)

        assert(bucket != null)
        assert(bucket.getState().equals(BucketState.extant))
        byte[] content = 'fakecontent123'.getBytes()

        ObjectEntity objectEntity = ObjectEntity.newInitializedForCreate(bucket, "testket", content.length, user)
        objectEntity.setAcl(acp)
        ObjectEntity resultEntity = OsgObjectFactory.getFactory().createObject(provider, objectEntity, new ByteArrayInputStream(content), null, user)

        assert(resultEntity != null)
        assert(resultEntity.getState().equals(ObjectState.extant))

        //Do the delete
        OsgObjectFactory.getFactory().actuallyDeleteObject(provider, objectEntity, user)

        try {
            ObjectEntity found = ObjectMetadataManagers.getInstance().lookupObject(bucket, "testkey", null)
            fail('Should have gotten no-key exception on metadata lookup')
        } catch(NoSuchElementException e) {
            //Correctly caught this exception
        }

        GetObjectType request = new GetObjectType()
        request.setUser(user)
        request.setKey(resultEntity.getObjectUuid())
        request.setBucket(bucket.getBucketUuid())
        try {
            GetObjectResponseType response = provider.getObject(request)
            fail('Should not find key on backend')
        } catch(EucalyptusCloudException e) {
            //Correctly caught the exception as expected
        }
    }

    /**
     * Expect deletion to throw an exception for not found object.
     * @throws Exception
     */
    @Test
    public void testDeleteNonExistentObject() throws Exception {
        User user = Accounts.lookupUserById(UnitTestSupport.getUsersByAccountName(UnitTestSupport.getTestAccounts().first()).first())
        String canonicalId = user.getAccount().getCanonicalId()
        AccessControlPolicy acp = new AccessControlPolicy()
        acp.setAccessControlList(new AccessControlList())
        acp = AclUtils.processNewResourcePolicy(user, acp, canonicalId)

        Bucket bucket = Bucket.getInitializedBucket("testbucket", user.getUserId(), acp, null)
        bucket = OsgBucketFactory.getFactory().createBucket(provider, bucket, UUID.randomUUID().toString(), user)

        assert(bucket != null)
        assert(bucket.getState().equals(BucketState.extant))

        def objects = TestUtils.createNObjects(ObjectMetadataManagers.getInstance(), 1, bucket, "testkey", 100, user)

        OsgObjectFactory.getFactory().actuallyDeleteObject(provider, objects.first(), user)

        try {
            //Do the delete
            ObjectEntity nonObject = new ObjectEntity().withBucket(bucket).withKey("nokey")
            OsgObjectFactory.getFactory().actuallyDeleteObject(provider, objects.first(), user)
            fail('DeleteObject should throw exception for nonexistent object')
        } catch(Exception e) {
            LOG.info("Caught expection as expected", e)
        }

    }

    /**
     * @throws Exception
     */
    @Test
    public void testMultipartUpload() throws Exception {
        //Set the min part size to 1 to allow small tests
        ObjectStorageProperties.MPU_PART_MIN_SIZE = 1;

        User user = Accounts.lookupUserById(UnitTestSupport.getUsersByAccountName(UnitTestSupport.getTestAccounts().first()).first())
        String canonicalId = user.getAccount().getCanonicalId()
        AccessControlPolicy acp = new AccessControlPolicy()
        acp.setAccessControlList(new AccessControlList())
        acp = AclUtils.processNewResourcePolicy(user, acp, canonicalId)

        Bucket bucket = Bucket.getInitializedBucket("testbucket", user.getUserId(), acp, null)
        bucket = OsgBucketFactory.getFactory().createBucket(provider, bucket, UUID.randomUUID().toString(), user)

        assert(bucket != null)
        assert(bucket.getState().equals(BucketState.extant))
        byte[] content = 'fakecontent123'.getBytes()
        def key = 'testkey'

        ObjectEntity objectEntity = ObjectEntity.newInitializedForCreate(bucket, key, -1, user)
        objectEntity.setAcl(acp)
        ObjectEntity resultEntity = OsgObjectFactory.getFactory().createMultipartUpload(provider, objectEntity, user)

        assert(resultEntity != null)
        assert(resultEntity.getState().equals(ObjectState.mpu_pending))
        ObjectEntity fetched = ObjectMetadataManagers.getInstance().lookupUpload(bucket, resultEntity.getUploadId());
        assert(fetched.getState() == ObjectState.mpu_pending)
        assert(fetched.getSize() == -1)
        assert(fetched.getUploadId() == resultEntity.getUploadId())

        List<Part> partList = new ArrayList<Part>(10);
        List<PartEntity> partEntities = new ArrayList<PartEntity>();

        for(int i = 1; i <= 10; i++) {
            PartEntity tmp = PartEntity.newInitializedForCreate(bucket, key, resultEntity.getUploadId(), i, content.length, user)
            partEntities.add(OsgObjectFactory.getFactory().createObjectPart(provider, fetched, tmp, new ByteArrayInputStream(content), user))

            PaginatedResult<PartEntity> partsList1 = MpuPartMetadataManagers.getInstance().listPartsForUpload(bucket, key, resultEntity.getUploadId(), null, 1000)
            assert(partsList1.getEntityList().size() == i)
            for(int j = 0 ; j < i; j++) {
                assert(partsList1.getEntityList().get(j).geteTag() == partEntities[j].geteTag())
            }
            partList.add(new Part(i, partEntities.last().geteTag()))
        }

        GetObjectType request = new GetObjectType()
        request.setUser(user)

        request.setKey(resultEntity.getObjectUuid())
        request.setBucket(bucket.getBucketUuid())
        try {
            GetObjectResponseType response = provider.getObject(request)
            fail("Should have failed to get key on incomplete MPU")
        } catch(NoSuchKeyException e) {
            //correct, not committed
        }

        //Complete the upload
        ObjectEntity finalObject = OsgObjectFactory.getFactory().completeMultipartUpload(provider, resultEntity, partList, user)
        assert(finalObject != null)
        assert(finalObject.geteTag() != null)
        assert(finalObject.getSize() == 10*content.length)
        assert(finalObject.getState() == ObjectState.extant)

        ObjectEntity foundObj = ObjectMetadataManagers.getInstance().lookupObject(bucket, key, null)
        assert(foundObj.getSize() == finalObject.getSize())
        assert(foundObj.geteTag() == finalObject.geteTag())
        assert(foundObj.getObjectUuid() == finalObject.getObjectUuid())

        GetObjectResponseType response = provider.getObject(request)
        assert(response.getEtag().equals(resultEntity.geteTag()))
        assert(response.getSize().equals(resultEntity.getSize()))
        byte[] buffer = new byte[content.length]
        response.getDataInputStream().read(buffer)
        for(int i = 0; i < buffer.size(); i++) {
            assert(buffer[i] == content[i])
        }
    }
}