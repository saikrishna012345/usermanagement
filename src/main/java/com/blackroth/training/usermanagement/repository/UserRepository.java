package com.blackroth.training.usermanagement.repository;

import com.blackroth.training.usermanagement.model.User;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Repository
public class UserRepository {

    private final Map<Long, User> store = new ConcurrentHashMap<>();
    private final AtomicLong idGenerator = new AtomicLong(0);

    public User save(User user) {
        if (user.getId() == null) {
            user.setId(idGenerator.incrementAndGet());
        }
        store.put(user.getId(), user);
        return user;
    }

    public List<User> findAll() {
        return new ArrayList<>(store.values());
    }

    public Optional<User> findById(Long id) {
        return Optional.ofNullable(store.get(id));
    }

    public boolean existsById(Long id) {
        return store.containsKey(id);
    }

    public void deleteById(Long id) {
        store.remove(id);
    }

    public List<User> search(String keyword) {
        String lower = keyword.toLowerCase();
        List<User> result = new ArrayList<>();
        for (User user : store.values()) {
            if (user.getName().toLowerCase().contains(lower)
                    || user.getEmail().toLowerCase().contains(lower)) {
                result.add(user);
            }
        }
        return result;
    }
}
